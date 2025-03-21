package com.openmediation.sdk.core;

import com.openmediation.sdk.bid.BidLoseReason;
import com.openmediation.sdk.bid.BidResponse;
import com.openmediation.sdk.bid.BidUtil;
import com.openmediation.sdk.core.runnable.AdsScheduleTask;
import com.openmediation.sdk.inspector.InspectorManager;
import com.openmediation.sdk.inspector.LogConstants;
import com.openmediation.sdk.inspector.logs.InventoryLog;
import com.openmediation.sdk.mediation.AdapterError;
import com.openmediation.sdk.mediation.CustomAdsAdapter;
import com.openmediation.sdk.utils.AdLog;
import com.openmediation.sdk.utils.AdRateUtil;
import com.openmediation.sdk.utils.AdapterUtil;
import com.openmediation.sdk.utils.AdsUtil;
import com.openmediation.sdk.utils.DeveloperLog;
import com.openmediation.sdk.utils.HandlerUtil;
import com.openmediation.sdk.utils.PlacementUtils;
import com.openmediation.sdk.utils.Preconditions;
import com.openmediation.sdk.utils.SceneUtil;
import com.openmediation.sdk.utils.WorkExecutor;
import com.openmediation.sdk.utils.cache.DataCache;
import com.openmediation.sdk.utils.constant.CommonConstants;
import com.openmediation.sdk.utils.constant.KeyConstants;
import com.openmediation.sdk.utils.error.Error;
import com.openmediation.sdk.utils.error.ErrorBuilder;
import com.openmediation.sdk.utils.error.ErrorCode;
import com.openmediation.sdk.utils.event.AdvanceEventId;
import com.openmediation.sdk.utils.event.EventId;
import com.openmediation.sdk.utils.event.EventUploadManager;
import com.openmediation.sdk.utils.helper.LrReportHelper;
import com.openmediation.sdk.utils.helper.WaterFallHelper;
import com.openmediation.sdk.utils.model.BaseInstance;
import com.openmediation.sdk.utils.model.Placement;
import com.openmediation.sdk.utils.model.PlacementInfo;
import com.openmediation.sdk.utils.model.Scene;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class AbstractInventoryAds extends AbstractAdsApi {

    protected Scene mScene;
    private int mInventorySize;
    // ad loading
    protected boolean isInLoadingProgress;
    // ad showing
    protected boolean isInShowingProgress;

    private final AtomicBoolean mLastAvailability = new AtomicBoolean(false);
    private final AtomicBoolean mDidScheduleTaskStarted = new AtomicBoolean(false);
    private final AtomicBoolean isAReadyReported = new AtomicBoolean(false);
    private final AtomicInteger mAllLoadFailedCount = new AtomicInteger(0);
    private int mInterval;

    protected abstract void onAvailabilityChanged(boolean available, Error error);

    /**
     * Instance shows ads
     *
     * @param instance the instance to show ads
     */
    protected abstract void insShow(BaseInstance instance);

    public AbstractInventoryAds() {
        super();
    }

    @Override
    public boolean isInventoryAdsType() {
        return true;
    }

    @Override
    protected boolean isReload() {
        return false;
    }

    protected int getInventorySize() {
        return mInventorySize;
    }

    protected List<BaseInstance> getAvailableInstance() {
        return InsManager.getInsWithStatus(mTotalIns, BaseInstance.MEDIATION_STATE.AVAILABLE);
    }

    protected int getIntervalTime() {
        return mInterval;
    }

    /**
     * Ends this loading
     */
    @Override
    protected void callbackLoadError(Error error) {
        AdLog.getSingleton().LogE("Ad load failed placementId: " + mPlacementId + ", " + error);
    }

    protected void callbackAvailableOnManual(BaseInstance instance) {
    }

    /**
     * Callback load success on manual.
     */
    protected void callbackLoadSuccessOnManual(BaseInstance instance) {
    }

    /**
     * Callback load failed on manual.
     *
     * @param error the error
     */
    protected void callbackLoadFailedOnManual(Error error) {
    }

    protected void callbackShowError(Error error) {
        isInShowingProgress = false;
        AdLog.getSingleton().LogE("Ad show failed placementId: " + mPlacementId + ", " + error);
    }

    /**
     * Callback ad closed.
     */
    protected void callbackAdClosed() {
    }

    @Override
    protected boolean shouldLoadBlock(OmManager.LOAD_TYPE type) {
        if (type == OmManager.LOAD_TYPE.MANUAL) {
            isManualTriggered = true;
            checkScheduleTaskStarted();
            checkHasExpiredInstance();
            int availableCount = InsManager.instanceCount(mTotalIns, BaseInstance.MEDIATION_STATE.AVAILABLE);
            if (availableCount > 0) {
                callbackAvailableOnManual(InsManager.getFirstAvailableIns(mTotalIns));
            }
        }

        if (isInLoadingProgress) {
            Error error = ErrorBuilder.build(ErrorCode.CODE_LOAD_INVALID_REQUEST
                    , ErrorCode.MSG_LOAD_INVALID_LOADING, ErrorCode.CODE_INTERNAL_REQUEST_PLACEMENTID);
            DeveloperLog.LogE("load ad for placement : " +
                    (Preconditions.checkNotNull(mPlacement) ? mPlacement.getId() : "") + " failed cause : " + error);
            AdsUtil.loadBlockedReport(Preconditions.checkNotNull(mPlacement) ? mPlacement.getId() : "", error);
            callbackLoadError(error);
            return true;
        }
        if (isInShowingProgress) {
            Error error = ErrorBuilder.build(ErrorCode.CODE_LOAD_INVALID_REQUEST
                    , ErrorCode.MSG_LOAD_INVALID_SHOWING, ErrorCode.CODE_INTERNAL_REQUEST_PLACEMENTID);
            DeveloperLog.LogE("load ad for placement : " +
                    (Preconditions.checkNotNull(mPlacement) ? mPlacement.getId() : "") + " failed cause : " + error);
            AdsUtil.loadBlockedReport(Preconditions.checkNotNull(mPlacement) ? mPlacement.getId() : "", error);
            callbackLoadError(error);
            return true;
        }
        return false;
    }

    @Override
    protected boolean shouldReplenishInventory(OmManager.LOAD_TYPE type) {
        checkHasExpiredInstance();
        int availableCount = InsManager.instanceCount(mTotalIns, BaseInstance.MEDIATION_STATE.AVAILABLE);

        if (type != OmManager.LOAD_TYPE.MANUAL) {
            String pid = mPlacement != null ? mPlacement.getId() : "";
            reportEvent(EventId.ATTEMPT_TO_BRING_NEW_FEED, PlacementUtils.placementEventParams(pid));
            if (availableCount > 0) {
                reportEvent(EventId.AVAILABLE_FROM_CACHE, PlacementUtils.placementEventParams(pid));
            }
        }

        if (availableCount >= mInventorySize) {
            DeveloperLog.LogD("Inventory is full, cancel this request");
            Error error = ErrorBuilder.build(ErrorCode.CODE_LOAD_INVALID_REQUEST
                    , "Inventory is full, cancel this request", ErrorCode.CODE_INTERNAL_UNKNOWN_OTHER);
            AdsUtil.loadBlockedReport(Preconditions.checkNotNull(mPlacement) ? mPlacement.getId() : "", error);
            return false;
        }
        return true;
    }

    @Override
    protected PlacementInfo getPlacementInfo() {
        return null;
    }

    @Override
    protected void resetBeforeGetInsOrder() {
        isAReadyReported.set(false);
        isInLoadingProgress = true;
        removeBidResponseWhenLoad();
    }

    @Override
    protected void setCurrentPlacement(Placement placement) {
        super.setCurrentPlacement(placement);
        mInventorySize = placement.getCs();
    }

    @Override
    protected void inventoryAdsReportAReady() {
        //when not trigger by init, checks cache before aReady reporting
        if (mLoadType != OmManager.LOAD_TYPE.INIT) {
            int availableCount = InsManager.instanceCount(mTotalIns, BaseInstance.MEDIATION_STATE.AVAILABLE);
            if (availableCount > 0) {
                isAReadyReported.set(true);
                LrReportHelper.report(mReqId, mRuleId, mPlacement.getId(), mLoadType.getValue(), mPlacement.getWfAbt(),
                        mPlacement.getWfAbtId(),
                        CommonConstants.WATERFALL_READY, 0);
            }
        }
    }

    @Override
    protected void finishLoad(Error error) {
        super.finishLoad(error);
        isInLoadingProgress = false;
    }

    @Override
    protected void startLoadAdsImpl(JSONObject clInfo, List<BaseInstance> totalIns) {
        List<BaseInstance> lastAvailableIns = InsManager.getInsWithStatus(mTotalIns, BaseInstance.MEDIATION_STATE.AVAILABLE);
        if (lastAvailableIns != null && !lastAvailableIns.isEmpty()) {
            InsManager.reOrderIns(lastAvailableIns, totalIns);
        }
        mTotalIns.clear();
        mTotalIns.addAll(totalIns);
        InsManager.resetInsStateOnClResponse(mTotalIns);
        DeveloperLog.LogD("TotalIns is : " + mTotalIns.toString());
        reSizeInventorySize();
        DeveloperLog.LogD("after cl, Inventory size is : " + mInventorySize);
        if (mPlacement != null) {
            WaterFallHelper.getS2sBidResponse(mPlacement, clInfo);
        }
        HandlerUtil.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                initOrFetchNextAdapter();
            }
        });
    }

    @Override
    protected List<BidResponse> appendLastBidResult() {
        List<BidResponse> responses = null;
        if (hasAvailableInventory()) {
            responses = new ArrayList<>();
            List<BaseInstance> insList = InsManager.getInsIdWithStatus(mTotalIns, BaseInstance.MEDIATION_STATE.AVAILABLE);
            for (BaseInstance ins : insList) {
                BidResponse bidResponse = ins.getBidResponse();
                if (bidResponse != null) {
                    responses.add(bidResponse);
                }
            }
        }
        return responses;
    }

    protected void showAd(String scene) {
        if (!shouldShowAd(scene)) {
            return;
        }
        for (BaseInstance in : mTotalIns) {
            if (in == null) {
                continue;
            }
            // TODO
//            BaseInstance.MEDIATION_STATE state = in.getMediationState();
//            if (state == BaseInstance.MEDIATION_STATE.INIT_PENDING
//                    || state == BaseInstance.MEDIATION_STATE.LOAD_PENDING
//                    || state == BaseInstance.MEDIATION_STATE.NOT_AVAILABLE
//                    || state == BaseInstance.MEDIATION_STATE.NOT_INITIATED) {
//                continue;
//            }
//            if (!isInsAvailable(in)) {
//                resetMediationStateAndNotifyLose(in, EventId.INSTANCE_PAYLOAD_NOT_AVAILABLE);
//                continue;
//            }
            if (isInsAvailable(in)) {
                setActRef();
                AdLog.getSingleton().LogD("Ad show placementId: " + mPlacementId);
                notifyInsBidWin(in);
                DataCache.getInstance().setMEM(in.getKey() + KeyConstants.KEY_DISPLAY_SCENE, mScene.getN());
                DataCache.getInstance().setMEM(in.getKey() + KeyConstants.KEY_DISPLAY_ABT, mPlacement.getWfAbt());
                DataCache.getInstance().setMEM(in.getKey() + KeyConstants.KEY_DISPLAY_ABT_ID, mPlacement.getWfAbtId());
                //if availability changed from false to true
                if (shouldNotifyAvailableChanged(false)) {
                    onAvailabilityChanged(false, null);
                }
                insShow(in);
                return;
            }
        }
        Error error = ErrorBuilder.build(ErrorCode.CODE_SHOW_NO_AD_READY
                , ErrorCode.MSG_SHOW_NO_AD_READY, -1);
        DeveloperLog.LogE(error.toString());
        callbackShowError(error);
    }

    protected boolean isPlacementAvailable() {
        if (isInShowingProgress || !Preconditions.checkNotNull(mPlacement) || mTotalIns == null || mTotalIns.isEmpty()) {
            return false;
        }
        for (BaseInstance in : mTotalIns) {
            if (in == null) {
                continue;
            }
            // TODO
//            BaseInstance.MEDIATION_STATE state = in.getMediationState();
//            if (state == BaseInstance.MEDIATION_STATE.INIT_PENDING
//                    || state == BaseInstance.MEDIATION_STATE.LOAD_PENDING
//                    || state == BaseInstance.MEDIATION_STATE.NOT_AVAILABLE
//                    || state == BaseInstance.MEDIATION_STATE.NOT_INITIATED) {
//                continue;
//            }
//            if (!isInsAvailable(in)) {
//                resetMediationStateAndNotifyLose(in, EventId.INSTANCE_PAYLOAD_NOT_AVAILABLE);
//                continue;
//            }

            if (isInsAvailable(in)) {
                return true;
            }
//            return false;
        }
        return false;
    }

    /**
     * On ins init failed.
     *
     * @param instance the instance
     * @param error    the error
     */
    protected void onInsInitFailed(BaseInstance instance, Error error) {
        super.onInsInitFailed(instance, error);
        notifyLoadFailedInsBidLose(instance);
        if (shouldFinishLoad()) {
            boolean hasInventory = hasAvailableInventory();
            if (!hasInventory) {
                onAllLoadFailed();
                if (isManualTriggered) {
                    callbackLoadFailedOnManual(error);
                }
            }
            if (shouldNotifyAvailableChanged(hasInventory)) {
                onAvailabilityChanged(hasInventory, error);
            }
            notifyUnLoadInsBidLose();
        } else {
            initOrFetchNextAdapter();
        }
    }

    @Override
    public void onInsC2SBidSuccess(BaseInstance bidInstance, BidResponse response) {
        super.onInsC2SBidSuccess(bidInstance, response);
        DeveloperLog.LogD("AbstractInventoryAds before onInsC2SBidSuccess TotalIns: " + mTotalIns);
        InsManager.sort(mTotalIns, bidInstance);
        DeveloperLog.LogD("AbstractInventoryAds after onInsC2SBidSuccess sort TotalIns: " + mTotalIns);
    }

    /**
     * On ins ready.
     *
     * @param instance the instance
     */
    @Override
    protected synchronized void onInsLoadSuccess(BaseInstance instance, boolean reload) {
        super.onInsLoadSuccess(instance, reload);
        mAllLoadFailedCount.set(0);
        if (!shouldFinishLoad()) {
            initOrFetchNextAdapter();
        } else {
            notifyUnLoadInsBidLose();
        }
        if (shouldNotifyAvailableChanged(true)) {
            if (!isAReadyReported.get()) {
                isAReadyReported.set(true);
                LrReportHelper.report(instance.getReqId(), mRuleId, instance.getPlacementId(),
                        mLoadType.getValue(), mPlacement.getWfAbt(), mPlacement.getWfAbtId(),
                        CommonConstants.WATERFALL_READY, 0);
            }
            onAvailabilityChanged(true, null);
        }
        if (isManualTriggered) {
            callbackLoadSuccessOnManual(instance);
        }

        AdLog.getSingleton().LogD("Ad load success placementId: " + mPlacementId);
    }

    /**
     * On ins load failed.
     *
     * @param instance the instance
     * @param error    the error
     */
    @Override
    protected synchronized void onInsLoadFailed(BaseInstance instance, AdapterError error, boolean reload) {
        super.onInsLoadFailed(instance, error, reload);
        if (shouldFinishLoad()) {
            Error errorResult = new Error(ErrorCode.CODE_LOAD_NO_AVAILABLE_AD, ErrorCode.MSG_LOAD_NO_AVAILABLE_AD + "All ins load failed, PlacementId: " + mPlacementId, -1);
            DeveloperLog.LogE(errorResult.toString());
            boolean hasInventory = hasAvailableInventory();
            if (!hasInventory) {
                onAllLoadFailed();
                if (isManualTriggered) {
                    callbackLoadFailedOnManual(errorResult);
                }
            }
            if (shouldNotifyAvailableChanged(hasInventory)) {
                DeveloperLog.LogD("onInsLoadFailed shouldFinishLoad shouldNotifyAvailableChanged " + hasInventory);
                onAvailabilityChanged(hasInventory, errorResult);
            }
            notifyUnLoadInsBidLose();
        } else {
            initOrFetchNextAdapter();
        }
    }

    /**
     * On ins open.
     *
     * @param instance the instance
     */
    @Override
    protected void onInsShowSuccess(BaseInstance instance, Scene scene) {
        super.onInsShowSuccess(instance, scene);
        //if availability changed from false to true
//        if (shouldNotifyAvailableChanged(false)) {
//            onAvailabilityChanged(false, null);
//        }
        if (getPlacementType() != CommonConstants.NATIVE) {
            isInShowingProgress = true;
        }
        AdLog.getSingleton().LogD("Ad show success placementId: " + mPlacementId);
    }

    /**
     * On ins close.
     */
    @Override
    protected void onInsClosed(BaseInstance instance, Scene scene) {
        super.onInsClosed(instance, scene);
        isInShowingProgress = false;
        callbackAdClosed();
        boolean hasInventory = hasAvailableInventory();
        if (shouldNotifyAvailableChanged(hasInventory)) {
            onAvailabilityChanged(hasInventory, null);
        }
        loadAds(OmManager.LOAD_TYPE.CLOSE);
        AdLog.getSingleton().LogD("Ad close placementId: " + mPlacementId);
    }

    public int getAllLoadFailedCount() {
        return mAllLoadFailedCount.get();
    }

    public Map<Integer, Integer> getRfs() {
        if (mPlacement == null) {
            return null;
        }
        return mPlacement.getRfs();
    }

    /**
     * The default load interval
     *
     * @return interval sec
     */
    public int getDefaultInterval() {
        return 300;
    }

    protected void checkScheduleTaskStarted() {
        if (!mDidScheduleTaskStarted.get()) {
            scheduleLoadAdTask();
        }
    }

    /**
     * Check whether the instance has expired
     */
    private void checkHasExpiredInstance() {
        if (mTotalIns == null || mTotalIns.isEmpty()) {
            return;
        }
        for (BaseInstance instance : mTotalIns) {
            if (instance == null || instance.getMediationState() != BaseInstance.MEDIATION_STATE.AVAILABLE) {
                continue;
            }
            if (instance.isExpired()) {
                DeveloperLog.LogD("AbstractInventoryAds, Instance has expired: " + instance);
                resetMediationStateAndNotifyLose(instance, EventId.INSTANCE_PAYLOAD_EXPIRED);
                continue;
            }
            if (!isInsAvailable(instance)) {
                DeveloperLog.LogD("AbstractInventoryAds, Instance is not available: " + instance);
                resetMediationStateAndNotifyLose(instance, EventId.INSTANCE_PAYLOAD_NOT_AVAILABLE);
            }
        }
    }

    private boolean shouldShowAd(String scene) {
        Error error = checkShowAvailable(scene);
        if (Preconditions.checkNotNull(error)) {
            callbackShowError(error);
            return false;
        }

        if (AdRateUtil.shouldBlockScene(mPlacement.getId(), mScene)) {
            error = ErrorBuilder.build(ErrorCode.CODE_SHOW_SCENE_CAPPED
                    , ErrorCode.MSG_SHOW_SCENE_CAPPED, -1);
            callbackShowError(error);
            return false;
        }
        return true;
    }

    /**
     * Finishes load when ads count suffices or all instances have been loaded: sum of ready, initFailed,
     * loadFailed, Capped
     *
     * @return should finish load or not
     */
    private boolean shouldFinishLoad() {
        int readyCount = InsManager.instanceCount(mTotalIns, BaseInstance.MEDIATION_STATE.AVAILABLE, BaseInstance.MEDIATION_STATE.SKIP);
        int allLoadedCount = InsManager.instanceCount(mTotalIns, BaseInstance.MEDIATION_STATE.AVAILABLE,
                BaseInstance.MEDIATION_STATE.INIT_FAILED, BaseInstance.MEDIATION_STATE.LOAD_FAILED,
                BaseInstance.MEDIATION_STATE.CAPPED, BaseInstance.MEDIATION_STATE.SKIP);
        if (readyCount >= mInventorySize || allLoadedCount == mTotalIns.size()) {
            DeveloperLog.LogD("full of cache or loaded all ins, current load is finished : " +
                    readyCount);
            Error error = null;
            if (readyCount <=0) {
                error = new Error(ErrorCode.CODE_LOAD_NO_AVAILABLE_AD
                        , "all ins load failed", ErrorCode.CODE_INTERNAL_SERVER_ERROR);
            }
            finishLoad(error);
            return true;
        }
        return false;
    }

    /**
     * when all ins load failed and no cache, recoder failed load count, report event
     */
    @Override
    protected void onAllLoadFailed() {
        mAllLoadFailedCount.incrementAndGet();
        reportEvent(EventId.NO_MORE_OFFERS, AdsUtil.buildAbtReportData(mPlacement.getWfAbt(), mPlacement.getWfAbtId(),
                PlacementUtils.placementEventParams(mPlacement != null ? mPlacement.getId() : "")));
    }

    /**
     * schedules Load Ad Task
     */
    private void scheduleLoadAdTask() {
        if (mPlacementId == null) {
            return;
        }
        Map<Integer, Integer> rfs = mPlacement.getRfs();
        if (rfs == null || rfs.isEmpty()) {
            mDidScheduleTaskStarted.set(true);
            int delay = getDefaultInterval();
            mInterval = delay;
            DeveloperLog.LogD("post adsScheduleTask delay : " + delay);
            WorkExecutor.execute(new AdsScheduleTask(this, delay), delay,
                    TimeUnit.SECONDS);
            return;
        }
        Collection<Integer> values = rfs.values();
        int delay = 0;
        for (Integer value : values) {
            // get first positive delay number
            if (value > 0) {
                delay = value;
                break;
            }
        }
        if (delay > 0) {
            mDidScheduleTaskStarted.set(true);
            mInterval = delay;
            DeveloperLog.LogD("post adsScheduleTask delay : " + delay);
            WorkExecutor.execute(new AdsScheduleTask(this, delay), delay,
                    TimeUnit.SECONDS);
        }
    }

    protected boolean shouldNotifyAvailableChanged(boolean available) {
        if (isInShowingProgress) {
            DeveloperLog.LogD("shouldNotifyAvailableChanged : " + false + " because current is in showing");
            return false;
        }

        if ((isManualTriggered || mLastAvailability.get() != available)) {
            DeveloperLog.LogD("shouldNotifyAvailableChanged for placement: " + mPlacement + " " + true);
            mLastAvailability.set(available);
            return true;
        }
        DeveloperLog.LogD("shouldNotifyAvailableChanged for placement : " + mPlacement + " " + false);
        return false;
    }

    public void setInterval(int interval) {
        this.mInterval = interval;
    }

    /**
     * Inits an adapter and loads. Skips if already in progress
     */
    private synchronized void initOrFetchNextAdapter() {
        int canLoadCount = 0;
        for (BaseInstance instance : mTotalIns) {
            BaseInstance.MEDIATION_STATE state = instance.getMediationState();
            if (state == BaseInstance.MEDIATION_STATE.INIT_PENDING ||
                    state == BaseInstance.MEDIATION_STATE.LOAD_PENDING) {
                ++canLoadCount;
            } else if (state == BaseInstance.MEDIATION_STATE.NOT_INITIATED) {
                instance.setReqId(mReqId);
                //init first if not
                CustomAdsAdapter adsAdapter = AdapterUtil.getCustomAdsAdapter(instance.getMediationId());
                if (adsAdapter == null) {
                    instance.setMediationState(BaseInstance.MEDIATION_STATE.INIT_FAILED);
                } else {
                    ++canLoadCount;
                    instance.setAdapter(adsAdapter);
                    initInsAndSendEvent(instance);
                }
            } else if (state == BaseInstance.MEDIATION_STATE.INITIATED
                    || state == BaseInstance.MEDIATION_STATE.NOT_AVAILABLE) {
                ++canLoadCount;
                instance.setReqId(mReqId);
                loadInsAndSendEvent(instance);
            }

            int limit = getLoadLimit();
            if (canLoadCount >= limit) {
                AdsUtil.advanceEventReport(mPlacementId, AdvanceEventId.CODE_INS_LOAD_LIMIT,
                        AdvanceEventId.MSG_INS_LOAD_LIMIT + "canLoadCount = " + canLoadCount + ", getLoadLimit() = " + limit);
                return;
            }
        }
        if (canLoadCount == 0) {
            Error error = ErrorBuilder.build(ErrorCode.CODE_LOAD_NO_AVAILABLE_AD
                    , ErrorCode.MSG_LOAD_NO_AVAILABLE_AD + "no can load ins", -1);
            DeveloperLog.LogE(error.toString());
            boolean hasCache = hasAvailableInventory();
            if (hasCache) {
                if (shouldNotifyAvailableChanged(true)) {
                    onAvailabilityChanged(true, error);
                }
            } else {
                callbackLoadError(error);
            }
            finishLoad(error);
            AdsUtil.advanceEventReport(mPlacementId, AdvanceEventId.CODE_CAN_NOT_LOAD,
                    AdvanceEventId.MSG_CAN_NOT_LOAD);
        }
    }

    /**
     * @return limit of loadable instances
     */
    private int getLoadLimit() {
        //compares with server issued max concurrent number
        return Math.min(mPlacement.getBs(), mInventorySize -
                InsManager.instanceCount(mTotalIns, BaseInstance.MEDIATION_STATE.AVAILABLE));
    }

    /**
     * re-calculates cached ads count
     */
    private void reSizeInventorySize() {
        int size = mInventorySize;
        if (mPlacement != null) {
            size = mPlacement.getCs();
        }
        mInventorySize = Math.min(size, mTotalIns.size());
    }

    private void removeBidResponseWhenLoad() {
        List<BaseInstance> availableIns = InsManager.getInsIdWithStatus(mTotalIns, BaseInstance.MEDIATION_STATE.AVAILABLE);
        if (availableIns.isEmpty()) {
            return;
        }
        for (BaseInstance instance : availableIns) {
            BidResponse bidResponse = instance.getBidResponse();
            if (bidResponse != null && bidResponse.isExpired()) {
                AdsUtil.advanceEventReport(instance, AdvanceEventId.CODE_BID_RESPONSE_EXPIRED,
                        AdvanceEventId.MSG_BID_RESPONSE_EXPIRED);
                resetMediationStateAndNotifyLose(instance, EventId.INSTANCE_PAYLOAD_EXPIRED);
            }
        }
    }

    protected void resetMediationStateAndNotifyLose(BaseInstance instance, int eventId) {
        if (instance.getMediationState() == BaseInstance.MEDIATION_STATE.AVAILABLE) {
            instance.setMediationState(BaseInstance.MEDIATION_STATE.NOT_AVAILABLE);
            AdsUtil.advanceEventReport(instance, AdvanceEventId.CODE_AD_EXPIRED,
                    AdvanceEventId.MSG_AD_EXPIRED);

            InventoryLog inventoryLog = new InventoryLog();
            inventoryLog.setInstance(instance);
            inventoryLog.setEventTag(LogConstants.INVENTORY_OUT);
            InspectorManager.getInstance().addInventoryLog(isInventoryAdsType(), mPlacementId, inventoryLog);
        }
        if (instance.isBid() && instance.getBidResponse() != null) {
            InsManager.reportInsEvent(instance, eventId);
        }
        BidUtil.notifyLose(instance, BidLoseReason.INVENTORY_DID_NOT_MATERIALISE.getValue());
    }

    @Override
    protected void notifyUnLoadInsBidLose() {
        if (mTotalIns == null) {
            return;
        }
        for (BaseInstance in : mTotalIns) {
            if (in == null) {
                continue;
            }
            if ((in.getMediationState() == BaseInstance.MEDIATION_STATE.NOT_INITIATED ||
                    in.getMediationState() == BaseInstance.MEDIATION_STATE.NOT_AVAILABLE)) {
                BidUtil.notifyLose(in, BidLoseReason.LOST_TO_HIGHER_BIDDER.getValue());
            }
        }
    }

    private void reportEvent(int eventId, JSONObject data) {
        EventUploadManager.getInstance().uploadEvent(eventId, data);
    }

    /**
     * showing is available if
     * 1.Activity is available
     * 2.init finished
     * 3.placement isn't null
     */
    private Error checkShowAvailable(String scene) {
        if (isInShowingProgress) {
            DeveloperLog.LogE("show ad failed, current is showing");
            return ErrorBuilder.build(-1, "show ad failed, current is showing", -1);
        }
        if (!Preconditions.checkNotNull(mPlacement)) {
            DeveloperLog.LogD("placement is null");
            return ErrorBuilder.build(ErrorCode.CODE_SHOW_INVALID_ARGUMENT
                    , ErrorCode.MSG_SHOW_INVALID_ARGUMENT + "Placement not found", ErrorCode.CODE_INTERNAL_REQUEST_PLACEMENTID);
        }
        mScene = SceneUtil.getScene(mPlacement, scene);
        if (!Preconditions.checkNotNull(mScene)) {
            return ErrorBuilder.build(ErrorCode.CODE_SHOW_SCENE_NOT_FOUND
                    , ErrorCode.MSG_SHOW_SCENE_NOT_FOUND, -1);
        }
        return null;
    }

}

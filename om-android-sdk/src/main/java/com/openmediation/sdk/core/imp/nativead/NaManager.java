// Copyright 2021 ADTIMING TECHNOLOGY COMPANY LIMITED
// Licensed under the GNU Lesser General Public License Version 3

package com.openmediation.sdk.core.imp.nativead;


import com.openmediation.sdk.bid.BidResponse;
import com.openmediation.sdk.core.InsManager;
import com.openmediation.sdk.core.OmManager;
import com.openmediation.sdk.core.imp.InventoryCacheManager;
import com.openmediation.sdk.inspector.InspectorManager;
import com.openmediation.sdk.inspector.LogConstants;
import com.openmediation.sdk.inspector.logs.InventoryLog;
import com.openmediation.sdk.mediation.AdapterError;
import com.openmediation.sdk.mediation.AdnAdInfo;
import com.openmediation.sdk.nativead.AdInfo;
import com.openmediation.sdk.nativead.NativeAdListener;
import com.openmediation.sdk.nativead.NativeAdView;
import com.openmediation.sdk.utils.AdLog;
import com.openmediation.sdk.utils.DeveloperLog;
import com.openmediation.sdk.utils.error.Error;
import com.openmediation.sdk.utils.error.ErrorCode;
import com.openmediation.sdk.utils.event.EventId;
import com.openmediation.sdk.utils.model.BaseInstance;
import com.openmediation.sdk.utils.model.PlacementInfo;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class NaManager extends InventoryCacheManager implements NaManagerListener {

    private int width = -1;
    private int height = -1;
    private boolean isCallbackToUser = false;

    /**
     * NativeAd Cache
     */
    private final Map<AdInfo, NaInstance> mInstancesMap;

    public NaManager() {
        super();
        mInstancesMap = new ConcurrentHashMap<>();
    }

    public void initNativeAd() {
        checkScheduleTaskStarted();
    }

    public void addAdListener(NativeAdListener listener) {
        mListenerWrapper.addNativeAdListener(listener);
    }

    public void removeAdListener(NativeAdListener listener) {
        mListenerWrapper.removeNativeAdListener(listener);
    }

    public void setDisplayParams(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @Override
    protected void callbackAvailableOnManual(BaseInstance instance) {
        super.callbackAvailableOnManual(instance);
        onLoadSuccess(instance);
    }

    @Override
    protected void callbackLoadError(Error error) {
        super.callbackLoadError(error);
        mListenerWrapper.onNativeAdLoadFailed(mPlacementId, error);
    }

    @Override
    protected void callbackLoadSuccessOnManual(BaseInstance instance) {
        super.callbackLoadSuccessOnManual(instance);
        onLoadSuccess(instance);
    }

    @Override
    protected void callbackLoadFailedOnManual(Error error) {
        super.callbackLoadFailedOnManual(error);
        mListenerWrapper.onNativeAdLoadFailed(mPlacementId, error);
    }

    @Override
    protected boolean isInsAvailable(BaseInstance instance) {
        return (instance instanceof NaInstance) && (((NaInstance) instance).isNaAvailable());
    }

    @Override
    protected void initInsAndSendEvent(BaseInstance instance) {
        super.initInsAndSendEvent(instance);
        if (instance instanceof NaInstance) {
            NaInstance naInstance = (NaInstance) instance;
            naInstance.setNaManagerListener(this);
            naInstance.initNa(mActRefs.get());
        } else {
            instance.setMediationState(BaseInstance.MEDIATION_STATE.INIT_FAILED);
            onInsInitFailed(instance, new Error(ErrorCode.CODE_LOAD_UNKNOWN_ERROR,
                    "current is not an native adUnit", -1));
        }
    }

    @Override
    protected void insLoad(BaseInstance instance, Map<String, Object> extras) {
        super.insLoad(instance, extras);
        if (instance instanceof NaInstance) {
            NaInstance naInstance = (NaInstance) instance;
            naInstance.setNaManagerListener(this);
            if (width > -1) {
                extras.put("width", width);
            }

            if (height > -1) {
                extras.put("height", height);
            }
            naInstance.loadNa(mActRefs.get(), extras);
        }
    }

    @Override
    protected void onAvailabilityChanged(boolean available, Error error) {

    }

    @Override
    protected void insShow(BaseInstance instance) {

    }

    @Override
    protected PlacementInfo getPlacementInfo() {
        return new PlacementInfo(mPlacementId).getPlacementInfo(getPlacementType());
    }

    public void loadNativeAd() {
        isCallbackToUser = false;
        loadAds(OmManager.LOAD_TYPE.MANUAL);
    }

    public void destroy(AdInfo adInfo) {
        if (adInfo == null || !mInstancesMap.containsKey(adInfo)) {
            AdLog.getSingleton().LogD("NativeAd destroy failed: AdnNativeAd is null, PlacementId: " + mPlacementId);
            return;
        }
        NaInstance instance = mInstancesMap.remove(adInfo);
        destroyAdEvent(instance);
    }

    protected void destroyAdEvent(NaInstance instance) {
        if (instance != null && instance.getObject() instanceof AdnAdInfo) {
            instance.destroyNa((AdnAdInfo) instance.getObject());
        }
        InsManager.reportInsDestroyed(instance);
    }

    public void registerView(NativeAdView adView, AdInfo adInfo) {
        if (isDestroyed || adInfo == null || adInfo.isTemplateRender()) {
            return;
        }
        if (!mInstancesMap.containsKey(adInfo)) {
            AdLog.getSingleton().LogD("NativeAd registerView failed: AdnNativeAd is null, PlacementId: " + mPlacementId);
            return;
        }
        NaInstance instance = mInstancesMap.get(adInfo);
        if (instance != null && instance.getObject() instanceof AdnAdInfo) {
            instance.registerView(adView, (AdnAdInfo) instance.getObject());
        }
    }

    @Override
    public void onNativeAdInitSuccess(NaInstance instance) {
        loadInsAndSendEvent(instance);
    }

    @Override
    public void onNativeAdInitFailed(NaInstance instance, AdapterError error) {
        Error errorResult = new Error(ErrorCode.CODE_LOAD_FAILED_IN_ADAPTER, error.toString(), -1);
        onInsInitFailed(instance, errorResult);
    }

    @Override
    public void onNativeAdLoadSuccess(NaInstance instance, AdInfo adInfo) {
        mInstancesMap.put(adInfo, instance);
        onInsLoadSuccess(instance, false);
    }

    @Override
    public void onNativeAdLoadFailed(NaInstance instance, AdapterError error) {
        onInsLoadFailed(instance, error, false);
    }

    @Override
    public void onNativeAdImpression(NaInstance instance) {
        onInsShowSuccess(instance, null);
        notifyInsBidWin(instance);
        InsManager.onInsShow(instance, null);
        mListenerWrapper.onNativeAdImpression(mPlacementId, getAdInfoByIns(instance));
    }

    @Override
    public void onNativeAdAdClicked(NaInstance instance) {
        onInsClicked(instance, null);
        mListenerWrapper.onNativeAdClicked(mPlacementId, getAdInfoByIns(instance));
    }

    private void onLoadSuccess(BaseInstance instance) {
        if (isCallbackToUser) {
            return;
        }
        // available change
        shouldNotifyAvailableChanged(false);
        isCallbackToUser = true;
        AdInfo info = getAdInfoByIns(instance);
        if (info == null) {
            //TODO: load failed
            DeveloperLog.LogE("NativeAd load failed: AdInfo not found in InstancesMap, PlacementId: " + mPlacementId);
            AdLog.getSingleton().LogE("NativeAd load failed: AdInfo not found in InstancesMap, PlacementId: " + mPlacementId);
            Error error = new Error(ErrorCode.CODE_LOAD_FAILED_IN_ADAPTER, ErrorCode.ERROR_NO_FILL + "AdInfo not found in InstancesMap", -1);
            mListenerWrapper.onNativeAdLoadFailed(mPlacementId, error);
        } else {
            mListenerWrapper.onNativeAdLoaded(mPlacementId, info);
            instance.setMediationState(BaseInstance.MEDIATION_STATE.SKIP);
            
            InventoryLog inventoryLog = new InventoryLog();
            inventoryLog.setInstance(instance);
            inventoryLog.setEventTag(LogConstants.INVENTORY_OUT);
            InspectorManager.getInstance().addInventoryLog(isInventoryAdsType(), mPlacementId, inventoryLog);

            //loadAds(OmManager.LOAD_TYPE.CLOSE);
        }
    }

    private AdInfo getAdInfoByIns(BaseInstance instance) {
        if (mInstancesMap.containsValue(instance)) {
            Set<AdInfo> infos = mInstancesMap.keySet();
            for (AdInfo info : infos) {
                if (mInstancesMap.get(info).equals(instance)) {
                    return info;
                }
            }
        }
        return null;
    }

    @Override
    public void onBidSuccess(BaseInstance instance, BidResponse response) {
        onInsC2SBidSuccess(instance, response);
    }

    @Override
    public void onBidFailed(BaseInstance instance, String error) {
        onInsC2SBidFailed(instance, error);
    }

    @Override
    public void onAdExpired(BaseInstance instance) {
        resetMediationStateAndNotifyLose(instance, EventId.INSTANCE_PAYLOAD_EXPIRED);
    }
}

// Copyright 2021 ADTIMING TECHNOLOGY COMPANY LIMITED
// Licensed under the GNU Lesser General Public License Version 3
package com.openmediation.sdk.core.imp.banner;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.openmediation.sdk.banner.AdSize;
import com.openmediation.sdk.banner.BannerAdListener;
import com.openmediation.sdk.bid.BidResponse;
import com.openmediation.sdk.core.InsManager;
import com.openmediation.sdk.core.OmManager;
import com.openmediation.sdk.core.imp.HybridCacheManager;
import com.openmediation.sdk.mediation.AdapterError;
import com.openmediation.sdk.mediation.MediationUtil;
import com.openmediation.sdk.utils.AdsUtil;
import com.openmediation.sdk.utils.DensityUtil;
import com.openmediation.sdk.utils.DeveloperLog;
import com.openmediation.sdk.utils.HandlerUtil;
import com.openmediation.sdk.utils.PlacementUtils;
import com.openmediation.sdk.utils.cache.DataCache;
import com.openmediation.sdk.utils.constant.KeyConstants;
import com.openmediation.sdk.utils.crash.CrashUtil;
import com.openmediation.sdk.utils.device.DeviceUtil;
import com.openmediation.sdk.utils.error.Error;
import com.openmediation.sdk.utils.error.ErrorBuilder;
import com.openmediation.sdk.utils.error.ErrorCode;
import com.openmediation.sdk.utils.event.EventId;
import com.openmediation.sdk.utils.event.EventUploadManager;
import com.openmediation.sdk.utils.model.BaseInstance;
import com.openmediation.sdk.utils.model.PlacementInfo;

import java.util.Map;

public class BnManager extends HybridCacheManager implements BnManagerListener, View.OnAttachStateChangeListener {

    private FrameLayout mLytBanner;
    private HandlerUtil.HandlerHolder mRlwHandler;
    private RefreshTask mRefreshTask;

    public BnManager(String placementId, BannerAdListener listener) {
        super(placementId);
        mRlwHandler = new HandlerUtil.HandlerHolder(null, Looper.getMainLooper());
        mListenerWrapper.setBnListener(listener);
    }

    public void setAdSize(AdSize adSize) {
        mAdSize = adSize;
    }

    @Override
    public void loadAds(OmManager.LOAD_TYPE type) {
        if (type == OmManager.LOAD_TYPE.MANUAL) {
            AdsUtil.callActionReport(mPlacementId, 0, EventId.CALLED_LOAD);
        }
        super.loadAds(type);
    }

    @Override
    public void destroy() {
        if (mRlwHandler != null) {
            mRlwHandler.removeCallbacks(mRefreshTask);
            mRlwHandler = null;
        }
        if (mLytBanner != null) {
            mLytBanner.removeAllViews();
            mLytBanner = null;
        }
        super.destroy();
    }

    @Override
    protected void destroyAdEvent(BaseInstance instances) {
        BnInstance bnInstance = (BnInstance) instances;
        bnInstance.destroyBn();
        InsManager.reportInsDestroyed(instances);
    }

    @Override
    protected void onAdErrorCallback(Error error) {
        startRefreshTask();
        mListenerWrapper.onBannerAdLoadFailed(mPlacementId, error);
//        isManualTriggered = false;
    }

    @Override
    protected void onAdReadyCallback() {
        try {
            if (mCurrentIns != null) {
                if (mCurrentIns.getObject() instanceof View) {
                    View banner = (View) mCurrentIns.getObject();
                    banner.removeOnAttachStateChangeListener(this);
                    if (banner.getParent() != null) {
                        ViewGroup parent = (ViewGroup) banner.getParent();
                        parent.removeView(banner);
                    }
                    if (mLytBanner == null) {
                        mLytBanner = createBannerParent(MediationUtil.getContext());
                    }
                    banner.addOnAttachStateChangeListener(this);
                    mLytBanner.removeAllViews();
                    mLytBanner.addView(banner);
//                    releaseAdEvent();
                    //
                    mListenerWrapper.onBannerAdLoaded(mPlacementId, mLytBanner);
                    //                }
                } else {
                    if (isManualTriggered) {
                        Error error = ErrorBuilder.build(ErrorCode.CODE_LOAD_NO_AVAILABLE_AD,
                                ErrorCode.MSG_LOAD_NO_AVAILABLE_AD + "Banner Load Error", -1);
                        mListenerWrapper.onBannerAdLoadFailed(mPlacementId, error);
                    }
                }
            } else {
                if (isManualTriggered) {
                    Error error = ErrorBuilder.build(ErrorCode.CODE_LOAD_NO_AVAILABLE_AD,
                            ErrorCode.MSG_LOAD_NO_AVAILABLE_AD + "CurrentIns is null", -1);
                    mListenerWrapper.onBannerAdLoadFailed(mPlacementId, error);
                }
            }
//            isManualTriggered = false;
        } catch (Exception e) {
            if (isManualTriggered) {
                Error error = ErrorBuilder.build(ErrorCode.CODE_LOAD_NO_AVAILABLE_AD,
                        ErrorCode.MSG_LOAD_NO_AVAILABLE_AD + e.getMessage(), ErrorCode.CODE_INTERNAL_UNKNOWN_OTHER);
                mListenerWrapper.onBannerAdLoadFailed(mPlacementId, error);
            }
            CrashUtil.getSingleton().saveException(e);
        }
    }

//    @Override
//    protected void onAdClickCallback() {
//        mListenerWrapper.onBannerAdClicked(mPlacementId);
//    }

    @Override
    protected boolean isInsAvailable(BaseInstance instance) {
        return (instance instanceof BnInstance) && (((BnInstance) instance).isBnAvailable());
    }

    @Override
    protected void initInsAndSendEvent(BaseInstance instance) {
        super.initInsAndSendEvent(instance);
        if (!(instance instanceof BnInstance)) {
            instance.setMediationState(BaseInstance.MEDIATION_STATE.INIT_FAILED);
            onInsLoadFailed(instance, new AdapterError(ErrorCode.CODE_LOAD_UNKNOWN_ERROR,
                    "current is not an Banner adUnit"), !isManualTriggered);
            return;
        }
        BnInstance bnInstance = (BnInstance) instance;
        bnInstance.setBnManagerListener(this);
        bnInstance.initBn(mActRefs.get());
    }

    @Override
    protected void insLoad(BaseInstance instance, Map<String, Object> extras) {
        super.insLoad(instance, extras);
        if (!isManualTriggered) {
            EventUploadManager.getInstance().uploadEvent(EventId.INSTANCE_RELOAD, InsManager.buildReportData(instance));
        }
        if (mAdSize != null) {
            extras.put("width", String.valueOf(mAdSize.getWidth()));
            extras.put("height", String.valueOf(mAdSize.getHeight()));
            extras.put("description", mAdSize.getDescription());
        }
        DataCache.getInstance().setMEM(instance.getKey() + KeyConstants.KEY_DISPLAY_ABT, mPlacement.getWfAbt());
        DataCache.getInstance().setMEM(instance.getKey() + KeyConstants.KEY_DISPLAY_ABT_ID, mPlacement.getWfAbtId());
        BnInstance bnInstance = (BnInstance) instance;
        bnInstance.setBnManagerListener(this);
        bnInstance.loadBn(mActRefs.get(), extras);
    }

    @Override
    protected PlacementInfo getPlacementInfo() {
        AdSize adSize = resetAdSize();
        return new PlacementInfo(mPlacementId).getBannerPlacementInfo(adSize.getWidth(), adSize.getHeight());
    }

    @Override
    public void onBannerAdInitSuccess(BnInstance instance) {
        loadInsAndSendEvent(instance);
    }

    @Override
    public void onBannerAdInitFailed(BnInstance instance, AdapterError error) {
        onInsLoadFailed(instance, error, !isManualTriggered);
    }

    @Override
    public void onBannerAdLoadSuccess(BnInstance instance) {
        onInsLoadSuccess(instance, !isManualTriggered);
    }

    @Override
    public void onBannerAdLoadFailed(BnInstance instance, AdapterError error) {
        onInsLoadFailed(instance, error, !isManualTriggered);
    }

    @Override
    public void onBannerAdShowSuccess(BnInstance instance) {

    }

    @Override
    public void onBannerAdShowFailed(BnInstance instance, AdapterError error) {

    }

    @Override
    public void onBannerAdAdClicked(BnInstance instance) {
        onInsClicked(instance, null);
        mListenerWrapper.onBannerAdClicked(mPlacementId);
    }

    @Override
    public void onViewAttachedToWindow(View v) {
        startRefreshTask();
        onViewAttachToWindow();
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        v.removeOnAttachStateChangeListener(this);
        onViewDetachFromWindow();
    }

    private FrameLayout createBannerParent(Context context) {
        FrameLayout layout = new FrameLayout(context);
        layout.setBackgroundColor(Color.TRANSPARENT);
        return layout;
    }

    private AdSize resetAdSize() {
        AdSize adSize = mAdSize;
        if (adSize == null) {
            adSize = AdSize.BANNER;
        } else if (adSize == AdSize.SMART) {
            if (DeviceUtil.isLargeScreen(MediationUtil.getContext())) {
                adSize = AdSize.LEADERBOARD;
            } else {
                adSize = AdSize.BANNER;
            }
        }
        return adSize;
    }

    private void startRefreshTask() {
        if (mRefreshTask != null) {
            return;
        }
        if (mPlacement == null || isDestroyed) {
            return;
        }

        if (mRlwHandler == null) {
            return;
        }
        try {
            int rlw = mPlacement.getRlw();
            if (rlw <= 0) {
                DeveloperLog.LogE("BnManager, stop RefreshTask: Interval is 0");
                return;
            }

            if (mRefreshTask == null) {
                mRefreshTask = new RefreshTask(rlw);
            }
            mRlwHandler.postDelayed(mRefreshTask, rlw * 1000);
        } catch (Throwable e) {
            DeveloperLog.LogE("BnManager, startRefreshTask error: " + e.getMessage());
        }
    }

    @Override
    public void onBidSuccess(BaseInstance instance, BidResponse response) {
        onInsC2SBidSuccess(instance, response);
    }

    @Override
    public void onBidFailed(BaseInstance instance, String error) {
        onInsC2SBidFailed(instance, error);
    }

    private class RefreshTask implements Runnable {

        private int mInterval;

        RefreshTask(int interval) {
            mInterval = interval;
        }

        @Override
        public void run() {
            try {
                if (mPlacement != null && mPlacement.getRlw() > 0) {
                    mInterval = mPlacement.getRlw();
                }

                if (mInterval <= 0) {
                    DeveloperLog.LogE("BnManager, RefreshTask stop: Interval is 0");
                    return;
                }

                mRlwHandler.postDelayed(mRefreshTask, mInterval * 1000);

                if (mLytBanner != null && !mLytBanner.isShown()) {
                    DeveloperLog.LogE("BnManager, RefreshTask stop: !mLytBanner.isShown(): " + mPlacementId);
                    return;
                }

                // not in foreground, stop load AD
                if (!OmManager.getInstance().isInForeground()) {
                    return;
                }
                if (mLoadTs > mCallbackTs) {
                    return;
                }
                EventUploadManager.getInstance().uploadEvent(EventId.REFRESH_INTERVAL,
                        PlacementUtils.placementEventParams(mPlacement != null ? mPlacement.getId() : ""));
                loadAds(OmManager.LOAD_TYPE.INTERVAL);
            } catch (Throwable e) {
                DeveloperLog.LogE("BnManager, RefreshTask run error: " + e.getMessage());
            }
        }
    }

    public static boolean isVisible(View view) {
        if (view == null) {
            return false;
        }
        if (!view.isShown()) {
            return false;
        }
        Context context = MediationUtil.getContext();
        if (context == null) {
            return false;
        }
        final Rect actualPosition = new Rect();
        view.getGlobalVisibleRect(actualPosition);
        final Rect screen = new Rect(0, 0, DensityUtil.getPhoneWidth(context), DensityUtil.getPhoneHeight(context));
        return actualPosition.intersect(screen);
    }
}

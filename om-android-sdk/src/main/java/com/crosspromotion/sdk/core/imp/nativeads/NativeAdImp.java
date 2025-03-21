// Copyright 2019 ADTIMING TECHNOLOGY COMPANY LIMITED
// Licensed under the GNU Lesser General Public License Version 3

package com.crosspromotion.sdk.core.imp.nativeads;

import android.graphics.Bitmap;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import com.bumptech.glide.Glide;
import com.crosspromotion.sdk.bean.AdBean;
import com.crosspromotion.sdk.bean.AdMark;
import com.crosspromotion.sdk.core.AbstractAdsManager;
import com.crosspromotion.sdk.nativead.Ad;
import com.crosspromotion.sdk.nativead.NativeAdListener;
import com.crosspromotion.sdk.report.AdReport;
import com.crosspromotion.sdk.utils.Cache;
import com.crosspromotion.sdk.utils.ImageUtils;
import com.crosspromotion.sdk.utils.PUtils;
import com.crosspromotion.sdk.utils.ResDownloader;
import com.crosspromotion.sdk.utils.error.ErrorBuilder;
import com.crosspromotion.sdk.utils.error.ErrorCode;
import com.crosspromotion.sdk.view.AdMarketView;
import com.openmediation.sdk.nativead.AdIconView;
import com.openmediation.sdk.nativead.MediaView;
import com.openmediation.sdk.nativead.NativeAdView;
import com.openmediation.sdk.utils.DeveloperLog;
import com.openmediation.sdk.utils.IOUtil;
import com.openmediation.sdk.utils.WorkExecutor;
import com.openmediation.sdk.utils.constant.CommonConstants;
import com.openmediation.sdk.utils.crash.CrashUtil;

import java.io.File;
import java.util.List;


public final class NativeAdImp extends AbstractAdsManager implements View.OnClickListener, View.OnAttachStateChangeListener {
    private boolean isImpReported;
    private Ad mAd;

    public NativeAdImp(String placementId) {
        super(placementId);
    }

    @Override
    protected int getAdType() {
        return CommonConstants.NATIVE;
    }

    public void setListener(NativeAdListener adListener) {
        mListenerWrapper.setNativeListener(adListener);
    }

    public void registerNativeView(NativeAdView view) {
        drawNativeAdView(view);
        view.addOnAttachStateChangeListener(this);
    }

    @Override
    public void destroy() {
        super.destroy();
        mAd = null;
        mAdBean = null;
    }

    @Override
    protected void callbackAdsReady() {
        super.callbackAdsReady();
        mListenerWrapper.onNativeAdsReady(mPlacementId, mAd);
    }

    @Override
    protected void preLoadResImpl(AdBean adBean) {
        super.preLoadResImpl(adBean);
        AdMark adMark = adBean.getAdMark();
        if (adMark != null && !TextUtils.isEmpty(adMark.getLogo())) {
            final String logo = adMark.getLogo();
            WorkExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        ResDownloader.downloadFile(logo);
                    } catch (Exception ignored) {
                    }
                }
            });
        }
    }

    @Override
    protected void onAdsLoadSuccess(AdBean bean) {
        super.onAdsLoadSuccess(bean);
        try {
            List<String> imgUrls = mAdBean.getMainimgUrl();
            if (imgUrls == null || imgUrls.isEmpty()) {
                onAdsLoadFailed(ErrorBuilder.build(ErrorCode.CODE_LOAD_RESOURCE_ERROR));
                return;
            }
            File contentFile = Cache.getCacheFile(mContext,
                    imgUrls.get(0), null);
            if (contentFile == null) {
                onAdsLoadFailed(ErrorBuilder.build(ErrorCode.CODE_LOAD_RESOURCE_ERROR));
                return;
            }
            Bitmap icon = ImageUtils.getBitmap(Cache.getCacheFile(mContext, mAdBean.getIconUrl(), null));

            Ad.Builder builder = new Ad.Builder();
            builder.title(mAdBean.getTitle())
                    .description(mAdBean.getDescription())
                    .cta(mAdBean.getName())
                    .rawContent(contentFile)
                    .icon(icon);

            mAd = builder.build();
            callbackAdsReady();
        } catch (Exception e) {
            onAdsLoadFailed(ErrorBuilder.build(ErrorCode.CODE_LOAD_UNKNOWN_EXCEPTION));
            CrashUtil.getSingleton().saveException(e);
            DeveloperLog.LogD("Native", e);
        }
    }

    @Override
    public void onClick(View v) {
        if (mAdBean == null) {
            return;
        }
        AdReport.CLKReport(mContext, mPlacementId, mAdBean);
        PUtils.doClick(mContext, mPlacementId, mAdBean);
        onAdsClicked();
    }

    @Override
    public void onViewAttachedToWindow(View v) {
        try {
            if (isImpReported || mAdBean == null) {
                return;
            }
            onAdsShowed();
            isImpReported = true;
        } catch (Exception e) {
            onAdsShowFailed(ErrorBuilder.build(ErrorCode.CODE_SHOW_UNKNOWN_EXCEPTION));
            DeveloperLog.LogE("native onViewAttachedToWindow ", e);
            CrashUtil.getSingleton().saveException(e);
        }
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        isImpReported = false;
        v.removeOnAttachStateChangeListener(this);
    }

    private void drawNativeAdView(NativeAdView adView) {
        if (adView == null || mAd == null) {
            onAdsShowFailed(ErrorBuilder.build(ErrorCode.CODE_SHOW_RESOURCE_ERROR));
            return;
        }
        if (adView.getMediaView() != null) {
            MediaView mediaView = adView.getMediaView();

            if (mAd.getRawContent() != null) {
                mediaView.removeAllViews();
                ImageView imageView = new ImageView(adView.getContext());
                mediaView.addView(imageView);
                mAd.fillImageView(adView.getContext(), imageView);

                imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                imageView.getLayoutParams().width = RelativeLayout.LayoutParams.MATCH_PARENT;
                imageView.getLayoutParams().height = RelativeLayout.LayoutParams.MATCH_PARENT;

            }
        }

        if (adView.getAdIconView() != null) {
            AdIconView adIconView = adView.getAdIconView();

            if (mAd.getIcon() != null) {
                adIconView.removeAllViews();
                ImageView iconImageView = new ImageView(adView.getContext());
                adIconView.addView(iconImageView);
                iconImageView.setImageBitmap(mAd.getIcon());
                iconImageView.getLayoutParams().width = RelativeLayout.LayoutParams.MATCH_PARENT;
                iconImageView.getLayoutParams().height = RelativeLayout.LayoutParams.MATCH_PARENT;
            } else {
                adIconView.setVisibility(View.GONE);
            }
        }
        if (adView.getCallToActionView() != null) {
            adView.getCallToActionView().setOnClickListener(this);
        }
        adView.setOnClickListener(this);
        setUpLogo(adView);
    }

    /**
     * draws logo based on adMark
     */
    private void setUpLogo(ViewGroup parent) {
        final AdMark adMark = mAdBean.getAdMark();
        if (adMark == null || TextUtils.isEmpty(adMark.getLogo()) || TextUtils.isEmpty(adMark.getLink())) {
            return;
        }
        String link = adMark.getLink();
        try {
            String logoUrl = adMark.getLogo();
            if (!TextUtils.isEmpty(logoUrl) && Cache.existCache(mContext, logoUrl)) {
                drawLogo(parent, ImageUtils.getBitmap(Cache.getCacheFile(mContext, logoUrl,
                        null)), link);
            } else {
                drawLogo(parent, null, link);
            }
        } catch (Exception e) {
            drawLogo(parent, null, link);
        }
    }

    private void drawLogo(ViewGroup parent, Bitmap bitmap, String link) {
        if (bitmap == null || TextUtils.isEmpty(link)) {
            return;
        }
        AdMarketView adMarketView = new AdMarketView(mContext, bitmap, link);
        parent.addView(adMarketView);
        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) adMarketView.getLayoutParams();
        params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        params.width = RelativeLayout.LayoutParams.WRAP_CONTENT;
        params.height = RelativeLayout.LayoutParams.WRAP_CONTENT;
        adMarketView.bringToFront();
    }

}

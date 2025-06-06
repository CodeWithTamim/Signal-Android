/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.webrtc;

import android.Manifest;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Point;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.Toolbar;
import androidx.compose.ui.platform.ComposeView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.constraintlayout.widget.Guideline;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.util.Consumer;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.MarginPageTransformer;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.google.android.material.button.MaterialButton;

import org.signal.core.util.DimensionUnit;
import org.signal.core.util.SetUtil;
import org.signal.core.util.ThreadUtil;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.AccessibleToggleButton;
import org.thoughtcrime.securesms.components.AvatarImageView;
import org.thoughtcrime.securesms.components.InsetAwareConstraintLayout;
import org.thoughtcrime.securesms.components.webrtc.v2.CallScreenControlsListener;
import org.thoughtcrime.securesms.components.webrtc.v2.PendingParticipantsListener;
import org.thoughtcrime.securesms.contacts.avatars.ContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.ProfileContactPhoto;
import org.thoughtcrime.securesms.events.CallParticipant;
import org.thoughtcrime.securesms.events.WebRtcViewModel;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.ringrtc.CameraState;
import org.thoughtcrime.securesms.components.webrtc.v2.PendingParticipantsState;
import org.thoughtcrime.securesms.stories.viewer.reply.reaction.MultiReactionBurstLayout;
import org.thoughtcrime.securesms.util.BlurTransformation;
import org.thoughtcrime.securesms.util.ThrottledDebouncer;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.thoughtcrime.securesms.util.views.Stub;
import org.thoughtcrime.securesms.webrtc.CallParticipantsViewState;
import org.webrtc.RendererCommon;
import org.whispersystems.signalservice.api.messages.calls.HangupMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class WebRtcCallView extends InsetAwareConstraintLayout {

  private static final String TAG = Log.tag(WebRtcCallView.class);

  private static final long TRANSITION_DURATION_MILLIS = 250;

  private WebRtcAudioOutputToggleButton audioToggle;
  private AccessibleToggleButton        videoToggle;
  private AccessibleToggleButton        micToggle;
  private ViewGroup                     smallLocalRenderFrame;
  private CallParticipantView           smallLocalRender;
  private View                          largeLocalRenderFrame;
  private TextureViewRenderer           largeLocalRender;
  private View                          largeLocalRenderNoVideo;
  private ImageView                     largeLocalRenderNoVideoAvatar;
  private TextView                      recipientName;
  private TextView                      status;
  private TextView                      incomingRingStatus;
  private CallScreenControlsListener    controlsListener;
  private RecipientId                   recipientId;
  private ImageView                     answer;
  private TextView                      answerWithoutVideoLabel;
  private ImageView                     cameraDirectionToggle;
  private AccessibleToggleButton        ringToggle;
  private PictureInPictureGestureHelper pictureInPictureGestureHelper;
  private ImageView                     overflow;
  private ImageView                     hangup;
  private View                          answerWithoutVideo;
  private View                          topGradient;
  private View                          footerGradient;
  private View                          startCallControls;
  private ViewPager2                    callParticipantsPager;
  private RecyclerView                  callParticipantsRecycler;
  private ConstraintLayout              largeHeader;
  private MaterialButton                startCall;
  private Stub<FrameLayout>             groupCallSpeakerHint;
  private Stub<View>                    groupCallFullStub;
  private View                          errorButton;
  private Guideline                     showParticipantsGuideline;
  private Guideline                     aboveControlsGuideline;
  private Guideline                     topFoldGuideline;
  private Guideline                     callScreenTopFoldGuideline;
  private AvatarImageView               largeHeaderAvatar;
  private int                           navBarBottomInset;
  private View                          fullScreenShade;
  private Toolbar                       collapsedToolbar;
  private Toolbar                       headerToolbar;
  private Stub<PendingParticipantsView> pendingParticipantsViewStub;
  private Stub<View>                    callLinkWarningCard;
  private RecyclerView                  groupReactionsFeed;
  private MultiReactionBurstLayout      reactionViews;
  private ComposeView                   raiseHandSnackbar;
  private View                          missingPermissionContainer;
  private MaterialButton                allowAccessButton;
  private Guideline                     callParticipantsOverflowGuideline;
  private View                          callControlsSheet;

  private WebRtcCallParticipantsPagerAdapter    pagerAdapter;
  private WebRtcCallParticipantsRecyclerAdapter recyclerAdapter;
  private WebRtcReactionsRecyclerAdapter        reactionsAdapter;
  private PictureInPictureExpansionHelper       pictureInPictureExpansionHelper;
  private PendingParticipantsListener           pendingParticipantsViewListener;

  private final Set<View> incomingCallViews    = new HashSet<>();
  private final Set<View> topViews             = new HashSet<>();
  private final Set<View> visibleViewSet       = new HashSet<>();
  private final Set<View> allTimeVisibleViews  = new HashSet<>();

  private final ThrottledDebouncer throttledDebouncer = new ThrottledDebouncer(TRANSITION_DURATION_MILLIS);
  private       WebRtcControls     controls           = WebRtcControls.NONE;

  private CallParticipantsViewState lastState;
  private ContactPhoto              previousLocalAvatar;
  private LayoutPositions           previousLayoutPositions = null;

  public WebRtcCallView(@NonNull Context context) {
    this(context, null);
  }

  public WebRtcCallView(@NonNull Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);

    inflate(context, R.layout.webrtc_call_view, this);
  }

  @SuppressWarnings("CodeBlock2Expr")
  @Override
  protected void onFinishInflate() {
    super.onFinishInflate();

    audioToggle                       = findViewById(R.id.call_screen_speaker_toggle);
    videoToggle                       = findViewById(R.id.call_screen_video_toggle);
    micToggle                         = findViewById(R.id.call_screen_audio_mic_toggle);
    smallLocalRenderFrame             = findViewById(R.id.call_screen_pip);
    smallLocalRender                  = findViewById(R.id.call_screen_small_local_renderer);
    largeLocalRenderFrame             = findViewById(R.id.call_screen_large_local_renderer_frame);
    largeLocalRender                  = findViewById(R.id.call_screen_large_local_renderer);
    largeLocalRenderNoVideo           = findViewById(R.id.call_screen_large_local_video_off);
    largeLocalRenderNoVideoAvatar     = findViewById(R.id.call_screen_large_local_video_off_avatar);
    recipientName                     = findViewById(R.id.call_screen_recipient_name);
    status                            = findViewById(R.id.call_screen_status);
    incomingRingStatus                = findViewById(R.id.call_screen_incoming_ring_status);
    answer                            = findViewById(R.id.call_screen_answer_call);
    answerWithoutVideoLabel           = findViewById(R.id.call_screen_answer_without_video_label);
    cameraDirectionToggle             = findViewById(R.id.call_screen_camera_direction_toggle);
    ringToggle                        = findViewById(R.id.call_screen_audio_ring_toggle);
    overflow                          = findViewById(R.id.call_screen_overflow_button);
    hangup                            = findViewById(R.id.call_screen_end_call);
    answerWithoutVideo                = findViewById(R.id.call_screen_answer_without_video);
    topGradient                       = findViewById(R.id.call_screen_header_gradient);
    footerGradient                    = findViewById(R.id.call_screen_footer_gradient);
    startCallControls                 = findViewById(R.id.call_screen_start_call_controls);
    callParticipantsPager             = findViewById(R.id.call_screen_participants_pager);
    callParticipantsRecycler          = findViewById(R.id.call_screen_participants_recycler);
    largeHeader                       = findViewById(R.id.call_screen_header);
    startCall                         = findViewById(R.id.call_screen_start_call_start_call);
    errorButton                       = findViewById(R.id.call_screen_error_cancel);
    groupCallSpeakerHint              = new Stub<>(findViewById(R.id.call_screen_group_call_speaker_hint));
    groupCallFullStub                 = new Stub<>(findViewById(R.id.group_call_call_full_view));
    showParticipantsGuideline         = findViewById(R.id.call_screen_show_participants_guideline);
    aboveControlsGuideline            = findViewById(R.id.call_screen_above_controls_guideline);
    topFoldGuideline                  = findViewById(R.id.fold_top_guideline);
    callScreenTopFoldGuideline        = findViewById(R.id.fold_top_call_screen_guideline);
    largeHeaderAvatar                 = findViewById(R.id.call_screen_header_avatar);
    fullScreenShade                   = findViewById(R.id.call_screen_full_shade);
    collapsedToolbar                  = findViewById(R.id.webrtc_call_view_toolbar_text);
    headerToolbar                     = findViewById(R.id.webrtc_call_view_toolbar_no_text);
    pendingParticipantsViewStub       = new Stub<>(findViewById(R.id.call_screen_pending_recipients));
    callLinkWarningCard               = new Stub<>(findViewById(R.id.call_screen_call_link_warning));
    groupReactionsFeed                = findViewById(R.id.call_screen_reactions_feed);
    reactionViews                     = findViewById(R.id.call_screen_reactions_container);
    raiseHandSnackbar                 = findViewById(R.id.call_screen_raise_hand_view);
    missingPermissionContainer        = findViewById(R.id.missing_permissions_container);
    allowAccessButton                 = findViewById(R.id.allow_access_button);
    callParticipantsOverflowGuideline = findViewById(R.id.call_screen_participants_overflow_guideline);
    callControlsSheet                 = findViewById(R.id.call_controls_info_parent);

    View decline      = findViewById(R.id.call_screen_decline_call);
    View answerLabel  = findViewById(R.id.call_screen_answer_call_label);
    View declineLabel = findViewById(R.id.call_screen_decline_call_label);

    callParticipantsPager.setPageTransformer(new MarginPageTransformer(ViewUtil.dpToPx(4)));

    pagerAdapter     = new WebRtcCallParticipantsPagerAdapter(this::toggleControls);
    recyclerAdapter  = new WebRtcCallParticipantsRecyclerAdapter();
    reactionsAdapter = new WebRtcReactionsRecyclerAdapter();

    callParticipantsPager.setAdapter(pagerAdapter);
    callParticipantsRecycler.setAdapter(recyclerAdapter);
    groupReactionsFeed.setAdapter(reactionsAdapter);

    DefaultItemAnimator animator = new DefaultItemAnimator();
    animator.setSupportsChangeAnimations(false);
    callParticipantsRecycler.setItemAnimator(animator);

    groupReactionsFeed.addItemDecoration(new WebRtcReactionsAlphaItemDecoration());
    groupReactionsFeed.setItemAnimator(new WebRtcReactionsItemAnimator());

    callParticipantsPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
      @Override
      public void onPageSelected(int position) {
        runIfNonNull(controlsListener, listener -> listener.onPageChanged(position == 0 ? CallParticipantsState.SelectedPage.GRID : CallParticipantsState.SelectedPage.FOCUSED));
      }
    });

    topViews.add(largeHeader);
    topViews.add(topGradient);

    incomingCallViews.add(answer);
    incomingCallViews.add(answerLabel);
    incomingCallViews.add(decline);
    incomingCallViews.add(declineLabel);
    incomingCallViews.add(footerGradient);
    incomingCallViews.add(incomingRingStatus);

    audioToggle.setOnAudioOutputChangedListener(webRtcAudioDevice -> {
      runIfNonNull(controlsListener, listener ->
      {
        if (Build.VERSION.SDK_INT >= 31) {
          if (webRtcAudioDevice.getDeviceId() != null) {
            listener.onAudioOutputChanged31(webRtcAudioDevice);
          } else {
            Log.e(TAG, "Attempted to change audio output to null device ID.");
          }
        } else {
          listener.onAudioOutputChanged(webRtcAudioDevice.getWebRtcAudioOutput());
        }
      });
    });

    videoToggle.setOnCheckedChangeListener((v, isOn) -> {
      if (!hasCameraPermission()) {
        videoToggle.setChecked(false);
      }
      runIfNonNull(controlsListener, listener -> listener.onVideoChanged(isOn));
    });

    micToggle.setOnCheckedChangeListener((v, isOn) -> {
      if (!hasAudioPermission()) {
        micToggle.setChecked(false);
      }
      runIfNonNull(controlsListener, listener -> listener.onMicChanged(isOn));
    });

    ringToggle.setOnCheckedChangeListener((v, isOn) -> {
      runIfNonNull(controlsListener, listener -> listener.onRingGroupChanged(isOn, ringToggle.isActivated()));
    });

    cameraDirectionToggle.setOnClickListener(v -> runIfNonNull(controlsListener, CallScreenControlsListener::onCameraDirectionChanged));
    smallLocalRender.findViewById(R.id.call_participant_switch_camera).setOnClickListener(v -> runIfNonNull(controlsListener, CallScreenControlsListener::onCameraDirectionChanged));

    overflow.setOnClickListener(v -> {
      runIfNonNull(controlsListener, CallScreenControlsListener::onOverflowClicked);
    });

    hangup.setOnClickListener(v -> runIfNonNull(controlsListener, CallScreenControlsListener::onEndCallPressed));
    decline.setOnClickListener(v -> runIfNonNull(controlsListener, CallScreenControlsListener::onDenyCallPressed));

    answer.setOnClickListener(v -> runIfNonNull(controlsListener, CallScreenControlsListener::onAcceptCallPressed));
    answerWithoutVideo.setOnClickListener(v -> runIfNonNull(controlsListener, CallScreenControlsListener::onAcceptCallWithVoiceOnlyPressed));

    pictureInPictureGestureHelper   = PictureInPictureGestureHelper.applyTo(smallLocalRenderFrame);
    pictureInPictureExpansionHelper = new PictureInPictureExpansionHelper(smallLocalRenderFrame, state -> {
      if (state == PictureInPictureExpansionHelper.State.IS_SHRUNKEN) {
        pictureInPictureGestureHelper.setBoundaryState(PictureInPictureGestureHelper.BoundaryState.COLLAPSED);
      } else {
        pictureInPictureGestureHelper.setBoundaryState(PictureInPictureGestureHelper.BoundaryState.EXPANDED);
      }
    });

    smallLocalRenderFrame.setOnClickListener(v -> {
      if (controlsListener != null) {
        controlsListener.onLocalPictureInPictureClicked();
      }
    });

    View smallLocalAudioIndicator = smallLocalRender.findViewById(R.id.call_participant_audio_indicator);
    int  audioIndicatorMargin     = (int) DimensionUnit.DP.toPixels(8f);
    ViewUtil.setLeftMargin(smallLocalAudioIndicator, audioIndicatorMargin);
    ViewUtil.setBottomMargin(smallLocalAudioIndicator, audioIndicatorMargin);

    startCall.setOnClickListener(v -> {
      Runnable onGranted = () -> {
        if (controlsListener != null) {
          startCall.setEnabled(false);
          controlsListener.onStartCall(videoToggle.isChecked());
        }
      };
      runIfNonNull(controlsListener, listener -> listener.onAudioPermissionsRequested(onGranted));
    });

    ColorMatrix greyScaleMatrix = new ColorMatrix();
    greyScaleMatrix.setSaturation(0);
    largeLocalRenderNoVideoAvatar.setAlpha(0.6f);
    largeLocalRenderNoVideoAvatar.setColorFilter(new ColorMatrixColorFilter(greyScaleMatrix));

    errorButton.setOnClickListener(v -> {
      if (controlsListener != null) {
        controlsListener.onCancelStartCall();
      }
    });

    collapsedToolbar.setNavigationOnClickListener(unused -> {
      if (controlsListener != null) {
        controlsListener.onNavigateUpClicked();
      }
    });

    collapsedToolbar.setOnMenuItemClickListener(item -> {
      if (item.getItemId() == R.id.action_info && controlsListener != null) {
        controlsListener.onCallInfoClicked();
        return true;
      }

      return false;
    });

    headerToolbar.setNavigationOnClickListener(unused -> {
      if (controlsListener != null) {
        controlsListener.onNavigateUpClicked();
      }
    });

    headerToolbar.setOnMenuItemClickListener(item -> {
      if (item.getItemId() == R.id.action_info && controlsListener != null) {
        controlsListener.onCallInfoClicked();
        return true;
      }

      return false;
    });

    missingPermissionContainer.setVisibility(hasCameraPermission() ? View.GONE : View.VISIBLE);

    allowAccessButton.setOnClickListener(v -> {
      runIfNonNull(controlsListener, listener -> listener.onVideoChanged(videoToggle.isEnabled()));
    });

    ConstraintLayout aboveControls = findViewById(R.id.call_controls_floating_parent);

    if (getContext().getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
      aboveControls.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
        pictureInPictureGestureHelper.setCollapsedVerticalBoundary(bottom + ViewUtil.getStatusBarHeight(v));
      });
    }

    SlideUpWithCallControlsBehavior behavior = (SlideUpWithCallControlsBehavior) ((CoordinatorLayout.LayoutParams) aboveControls.getLayoutParams()).getBehavior();
    Objects.requireNonNull(behavior).setOnTopOfControlsChangedListener(topOfControls -> {
      pictureInPictureGestureHelper.setExpandedVerticalBoundary(topOfControls);
    });

    if (callParticipantsOverflowGuideline != null) {
      callParticipantsRecycler.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
        callParticipantsOverflowGuideline.setGuidelineEnd(bottom - top);
      });
    }
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {

    final int   pipWidth      = smallLocalRenderFrame.getMeasuredWidth();
    final int   controlsWidth = callControlsSheet.getMeasuredWidth();
    final float protection    = DimensionUnit.DP.toPixels(16 * 4);
    final float requiredWidth = pipWidth + controlsWidth + protection;

    if (w > h && w >= requiredWidth) {
      pictureInPictureGestureHelper.allowCollapsedState();
    }
  }

  @Override
  public WindowInsets onApplyWindowInsets(WindowInsets insets) {
    navBarBottomInset = WindowInsetsCompat.toWindowInsetsCompat(insets).getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;

    if (lastState != null) {
      updateCallParticipants(lastState);
    }

    return super.onApplyWindowInsets(insets);
  }

  @Override
  public void onWindowSystemUiVisibilityChanged(int visible) {
    final Guideline statusBarGuideline = getStatusBarGuideline();
    if ((visible & SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
      pictureInPictureGestureHelper.setTopVerticalBoundary(collapsedToolbar.getBottom());
    } else if (statusBarGuideline != null) {
      pictureInPictureGestureHelper.setTopVerticalBoundary(statusBarGuideline.getBottom());
    } else {
      Log.d(TAG, "Could not update PiP gesture helper.");
    }
  }

  public void setControlsListener(@Nullable CallScreenControlsListener controlsListener) {
    this.controlsListener = controlsListener;
  }

  public void maybeDismissAudioPicker() {
    audioToggle.hidePicker();
  }

  public void setMicEnabled(boolean isMicEnabled) {
    micToggle.setChecked(hasAudioPermission() && isMicEnabled, false);
  }

  public void setPendingParticipantsViewListener(@Nullable PendingParticipantsListener listener) {
    pendingParticipantsViewListener = listener;
  }

  public void updatePendingParticipantsList(@NonNull PendingParticipantsState state) {
    if (state.isInPipMode()) {
      pendingParticipantsViewStub.setVisibility(View.GONE);
      return;
    }

    if (state.getPendingParticipantCollection().getUnresolvedPendingParticipants().isEmpty()) {
      if (pendingParticipantsViewStub.resolved()) {
        pendingParticipantsViewStub.get().setListener(pendingParticipantsViewListener);
        pendingParticipantsViewStub.get().applyState(state.getPendingParticipantCollection());
      }
    } else {
      pendingParticipantsViewStub.get().setListener(pendingParticipantsViewListener);
      pendingParticipantsViewStub.get().applyState(state.getPendingParticipantCollection());
    }
  }

  private boolean hasCameraPermission() {
    return Permissions.hasAll(getContext(), Manifest.permission.CAMERA);
  }

  private boolean hasAudioPermission() {
    return Permissions.hasAll(getContext(), Manifest.permission.RECORD_AUDIO);
  }

  public void updateCallParticipants(@NonNull CallParticipantsViewState callParticipantsViewState) {
    lastState = callParticipantsViewState;

    CallParticipantsState            state              = callParticipantsViewState.getCallParticipantsState();
    boolean                          isPortrait         = callParticipantsViewState.isPortrait();
    boolean                          isLandscapeEnabled = callParticipantsViewState.isLandscapeEnabled();
    List<WebRtcCallParticipantsPage> pages              = new ArrayList<>(2);

    if (!state.getCallState().isErrorState()) {
      if (!state.getGridParticipants().isEmpty()) {
        pages.add(WebRtcCallParticipantsPage.forMultipleParticipants(state.getGridParticipants(), state.getFocusedParticipant(), state.isInPipMode(), isPortrait, isLandscapeEnabled, state.getHideAvatar(), navBarBottomInset));
      }

      if (state.getFocusedParticipant() != CallParticipant.EMPTY && state.getAllRemoteParticipants().size() > 1) {
        pages.add(WebRtcCallParticipantsPage.forSingleParticipant(state.getFocusedParticipant(), state.isInPipMode(), isPortrait, isLandscapeEnabled));
      }
    }

    if (state.getGroupCallState().isNotIdle()) {
      if (state.getCallState() == WebRtcViewModel.State.CALL_PRE_JOIN) {
        if (state.isCallLink()) {
          TextView warningTextView = callLinkWarningCard.get().findViewById(R.id.call_screen_call_link_warning_textview);
          warningTextView.setText(SignalStore.phoneNumberPrivacy().isPhoneNumberSharingEnabled() ? R.string.WebRtcCallView__anyone_who_joins_pnp_enabled : R.string.WebRtcCallView__anyone_who_joins_pnp_disabled);
          callLinkWarningCard.setVisibility(View.VISIBLE);
        } else {
          callLinkWarningCard.setVisibility(View.GONE);
        }
        setStatus(state.getPreJoinGroupDescription(getContext()));
      } else if (state.getCallState() == WebRtcViewModel.State.CALL_CONNECTED && state.isInOutgoingRingingMode()) {
        callLinkWarningCard.setVisibility(View.GONE);
        setStatus(state.getOutgoingRingingGroupDescription(getContext()));
      } else if (state.getGroupCallState().isRinging()) {
        callLinkWarningCard.setVisibility(View.GONE);
        setStatus(state.getIncomingRingingGroupDescription(getContext()));
      } else {
        callLinkWarningCard.setVisibility(View.GONE);
      }
    }

    if (state.getGroupCallState().isNotIdle()) {
      boolean enabled = state.getParticipantCount().isPresent();
      collapsedToolbar.getMenu().getItem(0).setVisible(enabled);
      headerToolbar.getMenu().getItem(0).setVisible(enabled);
    } else {
      collapsedToolbar.getMenu().getItem(0).setVisible(false);
      headerToolbar.getMenu().getItem(0).setVisible(false);
    }

    pagerAdapter.submitList(pages);
    recyclerAdapter.submitList(state.getListParticipants());
    reactionsAdapter.submitList(state.getReactions());

    reactionViews.displayReactions(state.getReactions());

    boolean displaySmallSelfPipInLandscape = !isPortrait && isLandscapeEnabled;

    updateLocalCallParticipant(state.getLocalRenderState(), state.getLocalParticipant(), displaySmallSelfPipInLandscape);

    if (state.isLargeVideoGroup()) {
      adjustLayoutForLargeCount();
    } else {
      adjustLayoutForSmallCount();
    }
  }

  public void updateLocalCallParticipant(@NonNull WebRtcLocalRenderState state,
                                         @NonNull CallParticipant localCallParticipant,
                                         boolean displaySmallSelfPipInLandscape)
  {
    largeLocalRender.setMirror(localCallParticipant.getCameraDirection() == CameraState.Direction.FRONT);

    smallLocalRender.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
    largeLocalRender.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);

    localCallParticipant.getVideoSink().getLockableEglBase().performWithValidEglBase(eglBase -> {
      largeLocalRender.init(eglBase);
    });


    videoToggle.setChecked(hasCameraPermission() && localCallParticipant.isVideoEnabled(), false);
    smallLocalRender.setRenderInPip(true);
    smallLocalRender.setCallParticipant(localCallParticipant);
    smallLocalRender.setMirror(localCallParticipant.getCameraDirection() == CameraState.Direction.FRONT);

    if (state == WebRtcLocalRenderState.EXPANDED) {
      if (largeLocalRenderFrame.getVisibility() == View.VISIBLE) {
        smallLocalRenderFrame.setVisibility(View.VISIBLE);
        animatePipToExpandedRectangle(displaySmallSelfPipInLandscape, localCallParticipant.isMoreThanOneCameraAvailable());

        largeLocalRender.attachBroadcastVideoSink(null);
        largeLocalRenderFrame.setVisibility(View.GONE);
      } else {
        pictureInPictureExpansionHelper.beginExpandTransition();
        smallLocalRender.setSelfPipMode(CallParticipantView.SelfPipMode.EXPANDED_SELF_PIP, localCallParticipant.isMoreThanOneCameraAvailable());
      }

      return;
    } else if ((state.isAnySmall() || state == WebRtcLocalRenderState.GONE) && pictureInPictureExpansionHelper.isExpandedOrExpanding()) {
      pictureInPictureExpansionHelper.beginShrinkTransition();
      smallLocalRender.setSelfPipMode(pictureInPictureExpansionHelper.isMiniSize() ? CallParticipantView.SelfPipMode.MINI_SELF_PIP : CallParticipantView.SelfPipMode.NORMAL_SELF_PIP, localCallParticipant.isMoreThanOneCameraAvailable());

      if (state != WebRtcLocalRenderState.GONE) {
        return;
      }
    }

    switch (state) {
      case GONE:
        largeLocalRender.attachBroadcastVideoSink(null);
        largeLocalRenderFrame.setVisibility(View.GONE);
        smallLocalRenderFrame.setVisibility(View.GONE);

        break;
      case SMALL_RECTANGLE:
        smallLocalRenderFrame.setVisibility(View.VISIBLE);
        animatePipToLargeRectangle(displaySmallSelfPipInLandscape, localCallParticipant.isMoreThanOneCameraAvailable());

        largeLocalRender.attachBroadcastVideoSink(null);
        largeLocalRenderFrame.setVisibility(View.GONE);
        break;
      case SMALLER_RECTANGLE:
        smallLocalRenderFrame.setVisibility(View.VISIBLE);
        animatePipToSmallRectangle(displaySmallSelfPipInLandscape, localCallParticipant.isMoreThanOneCameraAvailable());

        largeLocalRender.attachBroadcastVideoSink(null);
        largeLocalRenderFrame.setVisibility(View.GONE);
        break;
      case LARGE:
        largeLocalRender.attachBroadcastVideoSink(localCallParticipant.getVideoSink());
        largeLocalRenderFrame.setVisibility(View.VISIBLE);

        largeLocalRenderNoVideo.setVisibility(View.GONE);
        largeLocalRenderNoVideoAvatar.setVisibility(View.GONE);

        smallLocalRenderFrame.setVisibility(View.GONE);
        break;
      case LARGE_NO_VIDEO:
        largeLocalRender.attachBroadcastVideoSink(null);
        largeLocalRenderFrame.setVisibility(View.VISIBLE);

        largeLocalRenderNoVideo.setVisibility(View.VISIBLE);
        largeLocalRenderNoVideoAvatar.setVisibility(View.VISIBLE);

        ContactPhoto localAvatar = new ProfileContactPhoto(localCallParticipant.getRecipient());

        if (!localAvatar.equals(previousLocalAvatar)) {
          previousLocalAvatar = localAvatar;
          Glide.with(getContext().getApplicationContext())
                  .load(localAvatar)
                  .transform(new CenterCrop(), new BlurTransformation(getContext(), 0.25f, BlurTransformation.MAX_RADIUS))
                  .diskCacheStrategy(DiskCacheStrategy.ALL)
                  .into(largeLocalRenderNoVideoAvatar);
        }

        smallLocalRenderFrame.setVisibility(View.GONE);
        break;
    }
  }

  public void setRecipient(@NonNull Recipient recipient) {
    collapsedToolbar.setTitle(recipient.getDisplayName(getContext()));
    recipientName.setText(recipient.getDisplayName(getContext()));

    if (recipient.getId() == recipientId) {
      return;
    }

    recipientId = recipient.getId();
    largeHeaderAvatar.setRecipient(recipient, false);
  }

  public void setStatus(@Nullable String status) {
    ThreadUtil.assertMainThread();
    this.status.setText(status);
    try {
      // Toolbar's subtitle view sometimes already has a parent somehow,
      // so we clear it out first so that it removes the view from its parent.
      // In addition, we catch the ISE to prevent a crash.
      collapsedToolbar.setSubtitle(null);
      collapsedToolbar.setSubtitle(status);
    } catch (IllegalStateException e) {
      Log.w(TAG, "IllegalStateException trying to set status on collapsed Toolbar.");
    }
  }

  public void setWebRtcControls(@NonNull WebRtcControls webRtcControls) {
    Set<View> lastVisibleSet = new HashSet<>(visibleViewSet);

    visibleViewSet.clear();

    if (webRtcControls.adjustForFold()) {
      showParticipantsGuideline.setGuidelineBegin(-1);
      showParticipantsGuideline.setGuidelineEnd(webRtcControls.getFold());
      topFoldGuideline.setGuidelineEnd(webRtcControls.getFold());
      callScreenTopFoldGuideline.setGuidelineEnd(webRtcControls.getFold());
    } else {
      showParticipantsGuideline.setGuidelineBegin(((LayoutParams) getStatusBarGuideline().getLayoutParams()).guideBegin);
      showParticipantsGuideline.setGuidelineEnd(-1);
      topFoldGuideline.setGuidelineEnd(0);
      callScreenTopFoldGuideline.setGuidelineEnd(0);
    }

    if (webRtcControls.displayStartCallControls()) {
      visibleViewSet.add(footerGradient);
      visibleViewSet.add(startCallControls);

      startCall.setText(webRtcControls.getStartCallButtonText());
      startCall.setEnabled(webRtcControls.isStartCallEnabled());
    }

    if (webRtcControls.displayErrorControls()) {
      visibleViewSet.add(footerGradient);
      visibleViewSet.add(errorButton);
    }

    if (webRtcControls.displayGroupCallFull()) {
      groupCallFullStub.get().setVisibility(View.VISIBLE);
      ((TextView) groupCallFullStub.get().findViewById(R.id.group_call_call_full_message)).setText(webRtcControls.getGroupCallFullMessage(getContext()));
    } else if (groupCallFullStub.resolved()) {
      groupCallFullStub.get().setVisibility(View.GONE);
    }

    if (webRtcControls.displayTopViews()) {
      visibleViewSet.addAll(topViews);
    }

    if (webRtcControls.displayIncomingCallButtons()) {
      visibleViewSet.addAll(incomingCallViews);

      incomingRingStatus.setText(webRtcControls.displayAnswerWithoutVideo() ? R.string.WebRtcCallView__signal_video_call: R.string.WebRtcCallView__signal_call);

      answer.setImageDrawable(AppCompatResources.getDrawable(getContext(), R.drawable.webrtc_call_screen_answer));
    }

    if (webRtcControls.displayAnswerWithoutVideo()) {
      visibleViewSet.add(answerWithoutVideo);
      visibleViewSet.add(answerWithoutVideoLabel);

      answer.setImageDrawable(AppCompatResources.getDrawable(getContext(), R.drawable.webrtc_call_screen_answer_with_video));
    }

    if (!webRtcControls.displayIncomingCallButtons()){
      incomingRingStatus.setVisibility(GONE);
    }

    if (webRtcControls.displayAudioToggle()) {
      audioToggle.setControlAvailability(webRtcControls.isEarpieceAvailableForAudioToggle(),
                                         webRtcControls.isBluetoothHeadsetAvailableForAudioToggle(),
                                         webRtcControls.isWiredHeadsetAvailableForAudioToggle());

      audioToggle.updateAudioOutputState(webRtcControls.getAudioOutput());
    }

    if (webRtcControls.displaySmallCallButtons()) {
      updateButtonStateForSmallButtons();
    } else {
      updateButtonStateForLargeButtons();
    }

    if (webRtcControls.displayRemoteVideoRecycler()) {
      callParticipantsRecycler.setVisibility(View.VISIBLE);
    } else {
      callParticipantsRecycler.setVisibility(View.GONE);
    }

    if (webRtcControls.showFullScreenShade()) {
      fullScreenShade.setVisibility(VISIBLE);
      visibleViewSet.remove(topGradient);
      visibleViewSet.remove(footerGradient);
    } else {
      fullScreenShade.setVisibility(GONE);
    }

    if (webRtcControls.displayReactions()) {
      visibleViewSet.add(reactionViews);
      visibleViewSet.add(groupReactionsFeed);
    }

    if (webRtcControls.displayRaiseHand()) {
      visibleViewSet.add(raiseHandSnackbar);
    }

    boolean forceUpdate = webRtcControls.adjustForFold() && !controls.adjustForFold();
    controls = webRtcControls;

    if (!controls.isFadeOutEnabled()) {
      boolean controlsVisible = true;
    }

    allTimeVisibleViews.addAll(visibleViewSet);

    if (!visibleViewSet.equals(lastVisibleSet) ||
        !controls.isFadeOutEnabled() ||
        (webRtcControls.showSmallHeader() && largeHeaderAvatar.getVisibility() == View.VISIBLE) ||
        (!webRtcControls.showSmallHeader() && largeHeaderAvatar.getVisibility() == View.GONE) ||
        forceUpdate)
    {
      throttledDebouncer.publish(() -> fadeInNewUiState(webRtcControls.showSmallHeader()));
    }

    onWindowSystemUiVisibilityChanged(getWindowSystemUiVisibility());
  }

  public @NonNull View getVideoTooltipTarget() {
    return videoToggle;
  }

  public @NonNull View getSwitchCameraTooltipTarget() {
    return smallLocalRenderFrame;
  }

  public void showSpeakerViewHint() {
    groupCallSpeakerHint.get().setVisibility(View.VISIBLE);
  }

  public void hideSpeakerViewHint() {
    if (groupCallSpeakerHint.resolved()) {
      groupCallSpeakerHint.get().setVisibility(View.GONE);
    }
  }

  private void animatePipToExpandedRectangle(boolean isLandscape, boolean moreThanOneCameraAvailable) {
    final Point dimens;
    if (isLandscape) {
      dimens = new Point(ViewUtil.dpToPx(PictureInPictureExpansionHelper.NORMAL_PIP_HEIGHT_DP),
                         ViewUtil.dpToPx(PictureInPictureExpansionHelper.NORMAL_PIP_WIDTH_DP));
    } else {
      dimens = new Point(ViewUtil.dpToPx(PictureInPictureExpansionHelper.NORMAL_PIP_WIDTH_DP),
                         ViewUtil.dpToPx(PictureInPictureExpansionHelper.NORMAL_PIP_HEIGHT_DP));
    }

    pictureInPictureExpansionHelper.startExpandedSizeTransition(dimens, new PictureInPictureExpansionHelper.Callback() {
      @Override
      public void onAnimationHasFinished() {
        pictureInPictureGestureHelper.enableCorners();
      }
    });

    smallLocalRender.setSelfPipMode(CallParticipantView.SelfPipMode.EXPANDED_SELF_PIP, moreThanOneCameraAvailable);
  }

  private void animatePipToLargeRectangle(boolean isLandscape, boolean moreThanOneCameraAvailable) {
    final Point dimens;
    if (isLandscape) {
      dimens = new Point(ViewUtil.dpToPx(PictureInPictureExpansionHelper.NORMAL_PIP_HEIGHT_DP),
                         ViewUtil.dpToPx(PictureInPictureExpansionHelper.NORMAL_PIP_WIDTH_DP));
    } else {
      dimens = new Point(ViewUtil.dpToPx(PictureInPictureExpansionHelper.NORMAL_PIP_WIDTH_DP),
                         ViewUtil.dpToPx(PictureInPictureExpansionHelper.NORMAL_PIP_HEIGHT_DP));
    }

    pictureInPictureExpansionHelper.startDefaultSizeTransition(dimens, new PictureInPictureExpansionHelper.Callback() {
      @Override
      public void onAnimationHasFinished() {
        pictureInPictureGestureHelper.enableCorners();
      }
    });

    smallLocalRender.setSelfPipMode(CallParticipantView.SelfPipMode.NORMAL_SELF_PIP, moreThanOneCameraAvailable);
  }

  private void animatePipToSmallRectangle(boolean isLandscape, boolean moreThanOneCameraAvailable) {
    final Point dimens;
    if (isLandscape) {
      dimens = new Point(ViewUtil.dpToPx(PictureInPictureExpansionHelper.MINI_PIP_HEIGHT_DP),
                         ViewUtil.dpToPx(PictureInPictureExpansionHelper.MINI_PIP_WIDTH_DP));
    } else {
      dimens = new Point(ViewUtil.dpToPx(PictureInPictureExpansionHelper.MINI_PIP_WIDTH_DP),
                         ViewUtil.dpToPx(PictureInPictureExpansionHelper.MINI_PIP_HEIGHT_DP));
    }

    pictureInPictureExpansionHelper.startDefaultSizeTransition(dimens,
                                                               new PictureInPictureExpansionHelper.Callback() {
                                                                 @Override
                                                                 public void onAnimationHasFinished() {
                                                                   pictureInPictureGestureHelper.lockToBottomEnd();
                                                                 }
                                                               });

    smallLocalRender.setSelfPipMode(CallParticipantView.SelfPipMode.MINI_SELF_PIP, moreThanOneCameraAvailable);
  }

  private void toggleControls() {
    controlsListener.toggleControls();
  }

  private void adjustLayoutForSmallCount() {
    adjustLayoutPositions(LayoutPositions.SMALL_GROUP);
  }

  private void adjustLayoutForLargeCount() {
    adjustLayoutPositions(LayoutPositions.LARGE_GROUP);
  }

  private void adjustLayoutPositions(@NonNull LayoutPositions layoutPositions) {
    if (previousLayoutPositions == layoutPositions) {
      return;
    }

    previousLayoutPositions = layoutPositions;

    ConstraintSet constraintSet = new ConstraintSet();
    constraintSet.setForceId(false);
    constraintSet.clone(this);

    constraintSet.connect(R.id.call_screen_reactions_feed,
                          ConstraintSet.BOTTOM,
                          layoutPositions.reactionBottomViewId,
                          ConstraintSet.TOP,
                          ViewUtil.dpToPx(layoutPositions.reactionBottomMargin));

    constraintSet.connect(pendingParticipantsViewStub.getId(),
                          ConstraintSet.BOTTOM,
                          layoutPositions.reactionBottomViewId,
                          ConstraintSet.TOP,
                          ViewUtil.dpToPx(layoutPositions.reactionBottomMargin));

    constraintSet.applyTo(this);
  }

  private void fadeInNewUiState(boolean showSmallHeader) {
    for (View view : SetUtil.difference(allTimeVisibleViews, visibleViewSet)) {
      view.setVisibility(GONE);
    }

    for (View view : visibleViewSet) {
      view.setVisibility(VISIBLE);
    }

    if (showSmallHeader) {
      collapsedToolbar.setEnabled(true);
      collapsedToolbar.setAlpha(1);
      headerToolbar.setEnabled(false);
      headerToolbar.setAlpha(0);
      largeHeader.setEnabled(false);
      largeHeader.setAlpha(0);
    } else {
      collapsedToolbar.setEnabled(false);
      collapsedToolbar.setAlpha(0);
      headerToolbar.setEnabled(true);
      headerToolbar.setAlpha(1);
      largeHeader.setEnabled(true);
      largeHeader.setAlpha(1);
    }
  }

  private static <T> void runIfNonNull(@Nullable T listener, @NonNull Consumer<T> listenerConsumer) {
    if (listener != null) {
      listenerConsumer.accept(listener);
    }
  }

  private void updateButtonStateForLargeButtons() {
    cameraDirectionToggle.setImageResource(R.drawable.webrtc_call_screen_camera_toggle);
    hangup.setImageResource(R.drawable.webrtc_call_screen_hangup);
    overflow.setImageResource(R.drawable.webrtc_call_screen_overflow_menu);
    micToggle.setBackgroundResource(R.drawable.webrtc_call_screen_mic_toggle);
    videoToggle.setBackgroundResource(R.drawable.webrtc_call_screen_video_toggle);
    audioToggle.setImageResource(R.drawable.webrtc_call_screen_speaker_toggle);
    ringToggle.setBackgroundResource(R.drawable.webrtc_call_screen_ring_toggle);
    overflow.setBackgroundResource(R.drawable.webrtc_call_screen_overflow_menu);
  }

  private void updateButtonStateForSmallButtons() {
    cameraDirectionToggle.setImageResource(R.drawable.webrtc_call_screen_camera_toggle_small);
    hangup.setImageResource(R.drawable.webrtc_call_screen_hangup_small);
    overflow.setImageResource(R.drawable.webrtc_call_screen_overflow_menu_small);
    micToggle.setBackgroundResource(R.drawable.webrtc_call_screen_mic_toggle_small);
    videoToggle.setBackgroundResource(R.drawable.webrtc_call_screen_video_toggle_small);
    audioToggle.setImageResource(R.drawable.webrtc_call_screen_speaker_toggle_small);
    ringToggle.setBackgroundResource(R.drawable.webrtc_call_screen_ring_toggle_small);
    overflow.setBackgroundResource(R.drawable.webrtc_call_screen_overflow_menu_small);
  }

  public void switchToSpeakerView() {
    if (pagerAdapter.getItemCount() > 0) {
      callParticipantsPager.setCurrentItem(pagerAdapter.getItemCount() - 1, false);
    }
  }

  public void setRingGroup(boolean shouldRingGroup) {
    ringToggle.setChecked(shouldRingGroup, false);
  }

  public void enableRingGroup(boolean enabled) {
    ringToggle.setActivated(enabled);
  }

  public void onControlTopChanged() {
  }
}

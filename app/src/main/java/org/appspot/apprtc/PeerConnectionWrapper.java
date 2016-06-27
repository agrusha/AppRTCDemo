package org.appspot.apprtc;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import org.appspot.apprtc.util.GuiThreadExecutor;
import org.appspot.apprtc.util.LooperExecutor;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;
import org.webrtc.StatsReport;

import lombok.Getter;

public class PeerConnectionWrapper implements AppRTCClient.SignalingEvents, PeerConnectionClient.PeerConnectionEvents {
  private static final String TAG = "Peer connection wrapper";
  private final GuiThreadExecutor executor = GuiThreadExecutor.getInstance();
  private final Context context;
  private Toast logToast;
  private long callStartedTimeMs = 0;
  private boolean isError;
  @Getter private boolean iceConnected;
  private AppRTCAudioManager audioManager;
  private final AppRTCClient appRtcClient;
  private AppRTCClient.SignalingParameters signalingParameters;
  @Getter private PeerConnectionClient peerConnectionClient;

  public PeerConnectionWrapper(Context context) {
    this.context = context;
    appRtcClient = new WebSocketRTCClient(this, new LooperExecutor());
    peerConnectionClient = PeerConnectionClient.getInstance();
    PeerConnectionClient.PeerConnectionParameters peerConnectionParameters = new PeerConnectionClient.PeerConnectionParameters(
            false,
            false,
            false,
            0,
            0,
            0,
            0,
            null,
            false,
            false,
            0,
            null,
            false,
            false,
            false);
    peerConnectionClient.createPeerConnectionFactory(context, peerConnectionParameters, this);
  }

  private void runOnUiThread(Runnable action) {
    executor.execute(action);
  }

  private void logAndToast(String msg) {
    Log.d(TAG, msg);
    if (logToast != null) {
      logToast.cancel();
    }
    logToast = Toast.makeText(context, msg, Toast.LENGTH_SHORT);
    logToast.show();
  }

  @Override
  public void onConnectedToRoom(final AppRTCClient.SignalingParameters params) {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        onConnectedToRoomInternal(params);
      }
    });
  }

  private void onConnectedToRoomInternal(final AppRTCClient.SignalingParameters params) {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;

    signalingParameters = params;
    logAndToast("Creating peer connection, delay=" + delta + "ms");
    peerConnectionClient.createPeerConnection(/*rootEglBase.getEglBaseContext()*/ null,
            /*localRender*/ null, /*remoteRender*/null, signalingParameters);

    if (signalingParameters.initiator) {
      logAndToast("Creating OFFER...");
      // Create offer. Offer SDP will be sent to answering client in
      // PeerConnectionEvents.onLocalDescription event.
      peerConnectionClient.createOffer();
    } else {
      if (params.offerSdp != null) {
        peerConnectionClient.setRemoteDescription(params.offerSdp);
        logAndToast("Creating ANSWER...");
        // Create answer. Answer SDP will be sent to offering client in
        // PeerConnectionEvents.onLocalDescription event.
        peerConnectionClient.createAnswer();
      }
      if (params.iceCandidates != null) {
        // Add remote ICE candidates from room.
        for (IceCandidate iceCandidate : params.iceCandidates) {
          peerConnectionClient.addRemoteIceCandidate(iceCandidate);
        }
      }
    }
  }

  @Override
  public void onRemoteDescription(final SessionDescription sdp) {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (peerConnectionClient == null) {
          Log.e(TAG, "Received remote SDP for non-initilized peer connection.");
          return;
        }
        logAndToast("Received remote " + sdp.type + ", delay=" + delta + "ms");
        peerConnectionClient.setRemoteDescription(sdp);
        if (!signalingParameters.initiator) {
          logAndToast("Creating ANSWER...");
          // Create answer. Answer SDP will be sent to offering client in
          // PeerConnectionEvents.onLocalDescription event.
          peerConnectionClient.createAnswer();
        }
      }
    });
  }

  @Override
  public void onRemoteIceCandidate(final IceCandidate candidate) {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (peerConnectionClient == null) {
          Log.e(TAG,
                  "Received ICE candidate for non-initilized peer connection.");
          return;
        }
        peerConnectionClient.addRemoteIceCandidate(candidate);
      }
    });
  }

  @Override
  public void onChannelClose() {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        logAndToast("Remote end hung up; dropping PeerConnection");
        disconnect();
      }
    });
  }

  @Override
  public void onChannelError(String description) {
    reportError(description);
  }

  private void reportError(final String description) {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (!isError) {
          isError = true;
          disconnectWithErrorMessage(description);
        }
      }
    });
  }

  private void disconnectWithErrorMessage(final String errorMessage) {
    Log.e(TAG, "Critical error: " + errorMessage);
    disconnect();
  }

  // Disconnect from remote resources, dispose of local resources, and exit.
  public void disconnect() {
    if (appRtcClient != null) {
      appRtcClient.disconnectFromRoom();
//        appRtcClient = null;
    }
    if (peerConnectionClient != null) {
      peerConnectionClient.close();
      peerConnectionClient = null;
    }
    if (audioManager != null) {
      audioManager.close();
      audioManager = null;
    }
  }

  // Should be called from UI thread
  private void callConnected() {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;
    Log.i(TAG, "Call connected: delay=" + delta + "ms");
//      if (peerConnectionClient == null || isError) {
//        Log.w(TAG, "Call is connected in closed or error state");
//        return;
//      }
    // Update video view.
//      updateVideoView();
    // Enable statistics callback.
//      peerConnectionClient.enableStatsEvents(true, STAT_CALLBACK_PERIOD);
  }

  public void startCall(String roomName) {
    if (appRtcClient == null) {
      Log.e(TAG, "AppRTC client is not allocated for a call.");
      return;
    }
    callStartedTimeMs = System.currentTimeMillis();

    // Start room connection.
    logAndToast(context.getString(R.string.connecting_to,
            roomName));
    // TODO: adjust to new API
    String roomUri = "https://appr.tc";
    AppRTCClient.RoomConnectionParameters roomConnectionParameters = new AppRTCClient.RoomConnectionParameters(
            roomUri, roomName, false);
    appRtcClient.connectToRoom(roomConnectionParameters);

    // Create and audio manager that will take care of audio routing,
    // audio modes, audio device enumeration etc.
    audioManager = AppRTCAudioManager.create(context, new Runnable() {
              // This method will be called each time the audio state (number and
              // type of devices) has been changed.
              @Override
              public void run() {
                Log.i(TAG, "audio state changed");
              }
            }
    );
    // Store existing audio settings and change audio mode to
    // MODE_IN_COMMUNICATION for best possible VoIP performance.
    Log.d(TAG, "Initializing the audio manager...");
    audioManager.init();
  }

  //--------------------------------------------------------------------

  @Override
  public void onLocalDescription(final SessionDescription sdp) {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (appRtcClient != null) {
          logAndToast("Sending " + sdp.type + ", delay=" + delta + "ms");
          if (signalingParameters.initiator) {
            appRtcClient.sendOfferSdp(sdp);
          } else {
            appRtcClient.sendAnswerSdp(sdp);
          }
        }
      }
    });
  }

  @Override
  public void onIceCandidate(final IceCandidate candidate) {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (appRtcClient != null) {
          appRtcClient.sendLocalIceCandidate(candidate);
        }
      }
    });
  }

  @Override
  public void onIceConnected() {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        logAndToast("ICE connected, delay=" + delta + "ms");
        iceConnected = true;
        callConnected();
      }
    });
  }

  @Override
  public void onIceDisconnected() {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        logAndToast("ICE disconnected");
        iceConnected = false;
        disconnect();
      }
    });
  }

  @Override
  public void onPeerConnectionClosed() {
  }

  @Override
  public void onPeerConnectionStatsReady(StatsReport[] reports) {
  }

  @Override
  public void onPeerConnectionError(String description) {
    reportError(description);
  }
}

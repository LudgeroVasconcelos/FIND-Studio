package find.service.net.diogomarques.wifioppish.networking;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

import find.service.net.diogomarques.wifioppish.AndroidNetworkingFacade;
import find.service.net.diogomarques.wifioppish.AndroidPreferences;
import find.service.net.diogomarques.wifioppish.IDomainPreferences;
import find.service.net.diogomarques.wifioppish.IEnvironment;
import find.service.net.diogomarques.wifioppish.INetworkingFacade.OnAccessPointScanListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.CountDownTimer;
import android.os.Looper;
import android.util.Log;

public class WiFiDelegate {

	private static final String TAG = WiFiDelegate.class.getSimpleName();

	/* Dependencies */
	private final Context mContext;
	private final IEnvironment mEnvironment;

	private BroadcastReceiver scanReceiver;

	private boolean registered;

	public WiFiDelegate(Context context, IEnvironment environment) {
		mContext = context;
		mEnvironment = environment;
	}

	public BroadcastReceiver getScanReceiver(){
		return scanReceiver;
	}
	/*
	 * There is not API for checking if a receiver is registered. This is a
	 * workaround.
	 */
	public boolean safeUnregisterReceiver(BroadcastReceiver receiver) {
		boolean sucess = false;
		try {
			mContext.unregisterReceiver(receiver);
			sucess = true;
		} catch (IllegalArgumentException exception) {
			sucess = false;
			Log.w("", "Trying to unregister a receiver that isn't registered.");
		}
		return sucess;

	}

	public void scanForAP(int timeoutMilis,
			final OnAccessPointScanListener listener,
			final AndroidNetworkingFacade androidNetworkingFacade) {
		final IDomainPreferences preferences = mEnvironment.getPreferences();

		// TODO wake & wifi locks may be needed. check.
		// best case, a lock WifiManager.WIFI_MODE_SCAN_ONLY will suffice
		// worst case, set wifi to never sleep:
		// <uses-permission android:name="android.permission.WRITE_SETTINGS"/>
		// Settings.System.putInt(getContentResolver(),
		// Settings.System.WIFI_SLEEP_POLICY,
		// Settings.System.WIFI_SLEEP_POLICY_NEVER);

		// control flag to prevent scan after connection
		final AtomicBoolean connected = new AtomicBoolean(false);

		// activate WiFi, otherwise other methods may behave strangely, e.g.
		// returning nulls
		// TODO check if this always works. enabling takes some time, but can be
		// checked by the receiver, using the WIFI_STATE_ENABLED extra
		final WifiManager manager = (WifiManager) mContext
				.getSystemService(Context.WIFI_SERVICE);
		manager.setWifiEnabled(true);

		// start receiver for scans
		scanReceiver = new BroadcastReceiver() {

			@Override
			public void onReceive(Context context, Intent intent) {
				List<ScanResult> results = manager.getScanResults();
				// quick fix: getScanResults sometimes returns null, breaking
				// the for-each
				if (results == null) {
					results = new ArrayList<ScanResult>(0);
				}
				// Best signal check:
				// http://marakana.com/forums/android/examples/40.html
				ScanResult bestSignal = null;
				for (ScanResult result : results) {
					if (result.SSID.equals(preferences.getWifiSSID())) {
						Log.w(TAG, "Found " + preferences.getWifiSSID()
								+ " AP, signal " + result.level);
						if (bestSignal == null
								|| WifiManager.compareSignalLevel(
										bestSignal.level, result.level) < 0)
							bestSignal = result;
					}
				}
				if (bestSignal != null) {
					Log.w(TAG, "Settled for AP with signal  "
							+ bestSignal.level);
					Log.w(TAG, "Best BSSID: |" + bestSignal.BSSID + "|");
					WifiConfiguration configuration = androidNetworkingFacade
							.getWifiSoftAPConfiguration();
					configuration.SSID = "\"" + configuration.SSID + "\"";
					configuration.preSharedKey = "\""
							+ configuration.preSharedKey + "\"";
					int netId = manager.addNetwork(configuration);
					Log.w(TAG, "addNetwork -> netId = " + netId);
					if (netId == -1) {
						throw new IllegalStateException("Add network error.");
					}
					boolean disconnect = manager.disconnect();
					Log.w(TAG, "disconnect -> " + disconnect);
					boolean enableNetwork = manager.enableNetwork(netId, true);
					Log.w(TAG, "enableNetwork -> " + enableNetwork);
					boolean reconnect = manager.reconnect();
					Log.w(TAG, "reconnect -> " + reconnect);
					registered = !safeUnregisterReceiver(this);
					connected.set(true);
					listener.onAPConnection(bestSignal.BSSID);
				}
			}
		};

		Log.d(TAG, "scan results for ap registered");
		if(!registered) {
			mContext.registerReceiver(scanReceiver, new IntentFilter(
					WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
			registered = true;
		}
		// scan immediately one time - fast reconnect
		// "In this case nevertheless, the node will not scan for t_scan seconds (which might be very long) but immediately try to a new AP or becomes one within a few seconds (fast reconnect)."
		manager.startScan();
		if (!connected.get()) {
			scanTick(timeoutMilis, preferences.getScanPeriod(), manager,
					connected, 0, scanReceiver, listener);
		}

	}

	private void scanTick(final int timeoutMilis, final int delay,
			final WifiManager manager, final AtomicBoolean connected,
			final long totaltime, final BroadcastReceiver scanReceiver,
			final OnAccessPointScanListener listener) {
		Timer timer = new Timer();
		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				manager.startScan();
				if (!connected.get()) {
					if (totaltime >= timeoutMilis) {
						Log.w("", "Scan timeout");
						safeUnregisterReceiver(scanReceiver);
						listener.onScanTimeout();
						mEnvironment.getNetworkingFacade().setTimeInScan( 0);

					} else {
						//Log.w("", "Scan tick" + delay + " " + totaltime);
						if (!mEnvironment.getPreferences().isRunningLocally()
								&&!mEnvironment.getNetworkingFacade()
								.scanForInternet(listener)) {
						}else{
							mEnvironment.getNetworkingFacade().setTimeInScan( totaltime);
						}
						long totalTime = delay + totaltime;
						scanTick(timeoutMilis, delay, manager, connected,
								totalTime, scanReceiver, listener);
					}
				}else{
					mEnvironment.getNetworkingFacade().setTimeInScan( totaltime);

				}
			}
		}, delay);
	}

}

package org.rsalvaterra.fon;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import org.rsalvaterra.fon.activity.BasicPreferences;
import org.rsalvaterra.fon.blacklist.BlacklistProvider;
import org.rsalvaterra.fon.login.LoginManager;
import org.rsalvaterra.fon.login.LoginResult;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;

public final class WakefulIntentService extends IntentService {

	private static final int MINIMUM_SIGNAL_LEVEL = -80;
	private static final int NOTIFICATION_ID = 1;
	private static final int REQUEST_CODE = 1;
	private static final int CONNECTIVITY_CHECK_INTERVAL = 60;
	private static final int LOGOFF_HTTP_TIMEOUT = 2 * 1000;

	private static final long[] VIBRATE_PATTERN_SUCCESS = { 100, 250 };
	private static final long[] VIBRATE_PATTERN_FAILURE = { 100, 250, 100, 250 };

	private static final Comparator<ScanResult> BY_DESCENDING_SIGNAL_LEVEL = new Comparator<ScanResult>() {

		@Override
		public int compare(final ScanResult sr1, final ScanResult sr2) {
			return sr2.level - sr1.level;
		}
	};

	public WakefulIntentService() {
		super(WakefulIntentService.class.getName());
	}

	private static WifiConfiguration[] getConfiguredNetworks(final WifiManager wm) {
		final List<WifiConfiguration> wcl = wm.getConfiguredNetworks();
		if (wcl == null) {
			return null;
		}
		return wcl.toArray(new WifiConfiguration[wcl.size()]);
	}

	private static int getOtherId(final WifiConfiguration[] wca, final ScanResult[] sra, final boolean secureOnly) {
		if ((wca != null) && (wca.length != 0)) {
			final HashMap<String, Integer> wcm = new HashMap<String, Integer>();
			for (final WifiConfiguration wc : wca) {
				final String ssid = WakefulIntentService.stripQuotes(wc.SSID);
				if ((!secureOnly || (secureOnly && WakefulIntentService.isSecure(wc))) && !LoginManager.isSupported(ssid)) {
					wcm.put(ssid, Integer.valueOf(wc.networkId));
				}
			}
			for (final ScanResult sr : sra) {
				if (sr.level < WakefulIntentService.MINIMUM_SIGNAL_LEVEL) {
					break;
				}
				final Integer id = wcm.get(sr.SSID);
				if (id != null) {
					return id.intValue();
				}
			}
		}
		return -1;
	}

	private static ScanResult[] getScanResults(final WifiManager wm) {
		final List<ScanResult> srl = wm.getScanResults();
		final ScanResult[] sra = srl.toArray(new ScanResult[srl.size()]);
		Arrays.sort(sra, WakefulIntentService.BY_DESCENDING_SIGNAL_LEVEL);
		return sra;
	}

	private static boolean isConnected(final SupplicantState ss) {
		return (ss == SupplicantState.COMPLETED);
	}

	private static boolean isDisconnected(final SupplicantState ss) {
		return (ss == SupplicantState.INACTIVE) || (ss == SupplicantState.DISCONNECTED) || (ss == SupplicantState.SCANNING);
	}

	private static boolean isSecure(final WifiConfiguration wc) {
		return wc.allowedKeyManagement.get(KeyMgmt.WPA_PSK) || wc.allowedKeyManagement.get(KeyMgmt.WPA_EAP) || wc.allowedKeyManagement.get(KeyMgmt.IEEE8021X) || (wc.wepKeys[0] != null);
	}

	private static void logoff(final String url, final WifiManager wm) {
		if ((url != null) && (url.length() != 0)) {
			HttpUtils.get(url, WakefulIntentService.LOGOFF_HTTP_TIMEOUT);
		}
		wm.disconnect();
	}

	static boolean isAutoConnectEnabled(final Context c) {
		return PreferenceManager.getDefaultSharedPreferences(c).getBoolean(c.getString(R.string.key_autoconnect), true);
	}

	static String stripQuotes(final String ssid) {
		final int length = ssid.length();
		if ((length > 2) && (ssid.charAt(0) == '"') && (ssid.charAt(length - 1) == '"')) {
			return ssid.substring(1, length - 1);
		}
		return ssid;
	}

	private boolean areNotificationsEnabled() {
		return PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getString(R.string.key_notify), true);
	}

	private void cancelAll() {
		final AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
		final Intent i = new Intent(this, AlarmBroadcastReceiver.class);
		am.cancel(PendingIntent.getBroadcast(this, WakefulIntentService.REQUEST_CODE, i.setAction(Constants.KEY_SCAN), PendingIntent.FLAG_UPDATE_CURRENT));
		am.cancel(PendingIntent.getBroadcast(this, WakefulIntentService.REQUEST_CODE, i.setAction(Constants.KEY_LOGIN), PendingIntent.FLAG_UPDATE_CURRENT));
		((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(WakefulIntentService.NOTIFICATION_ID);
	}

	private void connect(final WifiManager wm) {
		final WifiInfo wi = wm.getConnectionInfo();
		if (wi == null) {
			return;
		}
		final SupplicantState ss = wi.getSupplicantState();
		if (WakefulIntentService.isDisconnected(ss)) {
			final WifiConfiguration[] wca = WakefulIntentService.getConfiguredNetworks(wm);
			final ScanResult[] sra = WakefulIntentService.getScanResults(wm);
			int id = WakefulIntentService.getOtherId(wca, sra, false);
			if (id == -1) {
				id = getFonId(wca, sra, wm);
				if ((id != -1) && wm.enableNetwork(id, true) && isReconnectEnabled()) {
					scheduleScan();
				}
			} else {
				wm.enableNetwork(id, true);
			}
		} else if (WakefulIntentService.isConnected(ss) && isReconnectEnabled() && LoginManager.isSupported(WakefulIntentService.stripQuotes(wi.getSSID()))) {
			final int id = WakefulIntentService.getOtherId(WakefulIntentService.getConfiguredNetworks(wm), WakefulIntentService.getScanResults(wm), isSecureEnabled());
			if (id != -1) {
				wm.enableNetwork(id, true);
			} else {
				scheduleScan();
			}
		}
	}

	private String getFailureTone() {
		return PreferenceManager.getDefaultSharedPreferences(this).getString(getString(R.string.key_failure), "");
	}

	private int getFonId(final WifiConfiguration[] wca, final ScanResult[] sra, final WifiManager wm) {
		if ((wca != null) && (wca.length != 0)) {
			for (final WifiConfiguration wc : wca) {
				if (!WakefulIntentService.isSecure(wc) && LoginManager.isSupported(WakefulIntentService.stripQuotes(wc.SSID))) {
					wm.removeNetwork(wc.networkId);
				}
			}
		}
		for (final ScanResult sr : sra) {
			if (sr.level < WakefulIntentService.MINIMUM_SIGNAL_LEVEL) {
				break;
			}
			if (LoginManager.isSupported(sr.SSID) && !BlacklistProvider.isBlacklisted(getContentResolver(), sr.BSSID)) {
				final WifiConfiguration wc = new WifiConfiguration();
				wc.SSID = '"' + sr.SSID + '"';
				wc.BSSID = sr.BSSID;
				wc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
				return wm.addNetwork(wc);
			}
		}
		return -1;
	}

	private String getPassword() {
		return PreferenceManager.getDefaultSharedPreferences(this).getString(getString(R.string.key_password), "").trim();
	}

	private int getPeriod() {
		return Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(this).getString(getString(R.string.key_period), "300"));
	}

	private String getSuccessTone() {
		return PreferenceManager.getDefaultSharedPreferences(this).getString(getString(R.string.key_success), "");
	}

	private String getUsername() {
		return PreferenceManager.getDefaultSharedPreferences(this).getString(getString(R.string.key_username), "").trim();
	}

	private void handleError(final WifiManager wm, final LoginResult lr) {
		if (WakefulIntentService.isAutoConnectEnabled(this)) {
			final WifiInfo wi = wm.getConnectionInfo();
			BlacklistProvider.addToBlacklist(getContentResolver(), wi.getBSSID());
			wm.removeNetwork(wi.getNetworkId());
		} else {
			notifyFonError(lr);
		}
	}

	private void handleSuccess(final String ssid, final LoginResult lr, final boolean isfirst) {
		final Intent i = new Intent();
		final PendingIntent pi;
		final String text;
		if (WakefulIntentService.isAutoConnectEnabled(this)) {
			pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
			text = getString(R.string.notif_title_conn, ssid);
		} else {
			pi = PendingIntent.getService(this, WakefulIntentService.REQUEST_CODE, i.setClass(this, WakefulIntentService.class).setAction(Constants.KEY_LOGOFF).putExtra(Constants.KEY_LOGOFF_URL, lr.getLogOffUrl()), PendingIntent.FLAG_UPDATE_CURRENT);
			text = getString(R.string.notif_text_logoff);
		}
		if (isfirst) {
			notify(getString(R.string.notif_text_started), WakefulIntentService.VIBRATE_PATTERN_SUCCESS, Notification.FLAG_NO_CLEAR | Notification.FLAG_ONLY_ALERT_ONCE | Notification.FLAG_ONGOING_EVENT, getSuccessTone(), text, pi);
		}
		scheduleConnectivityCheck();
	}

	private boolean isReconnectEnabled() {
		return PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getString(R.string.key_reconnect), false);
	}

	private boolean isSecureEnabled() {
		return PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getString(R.string.key_secure), true);
	}

	private boolean isVibrationEnabled() {
		return PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getString(R.string.key_vibration), false);
	}

	private void login(final WifiManager wm, final boolean isFirst) {
		final String u = getUsername();
		final String p = getPassword();
		if ((u.length() == 0) || (p.length() == 0)) {
			notifyCredentialsError();
			return;
		}
		final String tuc = LoginManager.getTestUrlContent();
		if (tuc == null) {
			wm.removeNetwork(wm.getConnectionInfo().getNetworkId());
			return;
		}
		final LoginResult lr;
		final String ssid = WakefulIntentService.stripQuotes(wm.getConnectionInfo().getSSID());
		if (LoginManager.isFon(ssid)) {
			lr = LoginManager.fonLogin(tuc, u, p);
		} else if (LoginManager.isSfr(ssid)) {
			lr = LoginManager.sfrLogin(tuc, u, p);
		} else {
			return;
		}
		switch (lr.getResponseCode()) {
			case Constants.WISPR_RESPONSE_CODE_LOGIN_SUCCEEDED:
			case Constants.CUST_ALREADY_CONNECTED:
				handleSuccess(ssid, lr, isFirst);
				break;
			case Constants.WISPR_RESPONSE_CODE_RADIUS_ERROR:
			case Constants.WISPR_RESPONSE_CODE_NETWORK_ADMIN_ERROR:
			case Constants.FON_SESSION_LIMIT_EXCEEDED:
			case Constants.FON_SPOT_LIMIT_EXCEEDED:
			case Constants.FON_UNKNOWN_ERROR:
			case Constants.CUST_WISPR_NOT_PRESENT:
				handleError(wm, lr);
				break;
			case Constants.FON_NOT_ENOUGH_CREDIT:
			case Constants.FON_USER_IN_BLACK_LIST:
			case Constants.FON_NOT_AUTHORIZED:
			case Constants.FON_CUSTOMIZED_ERROR:
			case Constants.FON_INTERNAL_ERROR:
			case Constants.FON_INVALID_TEMPORARY_CREDENTIAL:
			case Constants.FON_AUTHORIZATION_CONNECTION_ERROR:
				notifyFonError(lr);
				break;
			case Constants.FON_INVALID_CREDENTIALS_ALT:
			case Constants.FON_INVALID_CREDENTIALS:
			case Constants.CUST_CREDENTIALS_ERROR:
				notifyCredentialsError();
				break;
			default:
				break;
		}
	}

	private void notify(final String title, final long[] vibratePattern, final int flags, final String ringtone, final String text, final PendingIntent pendingIntent) {
		final Notification notification = new Notification(R.drawable.ic_stat_fon, title, System.currentTimeMillis());
		if (areNotificationsEnabled()) {
			if (isVibrationEnabled()) {
				notification.vibrate = vibratePattern;
			}
			notification.sound = Uri.parse(ringtone);
		}
		notification.flags |= flags;
		notification.setLatestEventInfo(this, title, text, pendingIntent);
		((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(WakefulIntentService.NOTIFICATION_ID, notification);
	}

	private void notifyCredentialsError() {
		notifyError(getString(R.string.notif_title_cred_err), getString(R.string.notif_text_config));
	}

	private void notifyError(final String title, final String text) {
		notify(title, WakefulIntentService.VIBRATE_PATTERN_FAILURE, 0, getFailureTone(), text, PendingIntent.getActivity(this, WakefulIntentService.REQUEST_CODE, new Intent(this, BasicPreferences.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT));
	}

	private void notifyFonError(final LoginResult lr) {
		notifyError(getString(R.string.notif_title_fon_err, Integer.valueOf(lr.getResponseCode())), '"' + lr.getReplyMessage() + '"');
	}

	private void scheduleAction(final Intent intent, final int seconds) {
		((AlarmManager) getSystemService(Context.ALARM_SERVICE)).set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + (seconds * 1000), PendingIntent.getBroadcast(this, WakefulIntentService.REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT));
	}

	private void scheduleConnectivityCheck() {
		scheduleAction(new Intent(this, AlarmBroadcastReceiver.class).setAction(Constants.KEY_LOGIN).putExtra(Constants.KEY_FIRST, false), WakefulIntentService.CONNECTIVITY_CHECK_INTERVAL);
	}

	private void scheduleScan() {
		scheduleAction(new Intent(this, AlarmBroadcastReceiver.class).setAction(Constants.KEY_SCAN), getPeriod());
	}

	@Override
	protected void onHandleIntent(final Intent i) {
		final String a = i.getAction();
		if (a.equals(Constants.KEY_CANCEL_ALL)) {
			cancelAll();
		} else {
			final WifiManager wm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
			if (a.equals(Constants.KEY_CONNECT)) {
				connect(wm);
			} else if (a.equals(Constants.KEY_LOGIN)) {
				login(wm, i.getBooleanExtra(Constants.KEY_FIRST, false));
			} else if (a.equals(Constants.KEY_LOGOFF)) {
				WakefulIntentService.logoff(i.getStringExtra(Constants.KEY_LOGOFF_URL), wm);
			} else if (a.equals(Constants.KEY_SCAN)) {
				wm.startScan();
			}
		}
		WakefulBroadcastReceiver.releaseWakeLock(i);
	}

}

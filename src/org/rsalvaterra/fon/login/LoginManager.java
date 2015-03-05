package org.rsalvaterra.fon.login;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;

import org.apache.http.message.BasicNameValuePair;
import org.rsalvaterra.fon.HttpUtils;
import org.rsalvaterra.fon.R;
import org.rsalvaterra.fon.ResponseCodes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import android.content.Context;
import android.preference.PreferenceManager;
import android.util.Xml;

public final class LoginManager {

	private static final String CONNECTED = "CONNECTED";
	private static final String CONNECTION_TEST_URL = "http://cm.fon.mobi/android.txt";
	private static final String DEFAULT_LOGOFF_URL = "http://192.168.3.1:80/logoff";
	private static final String FON_MAC_PREFIX = "00:18:84";
	private static final String FON_USERNAME_PREFIX = "FON_WISPR/";
	private static final String LIVEDOOR_TARGET_URL = "https://vauth.lw.livedoor.com/fauth/index";
	private static final String TAG_WISPR = "WISPAccessGatewayParam";
	private static final String TAG_WISPR_PASSWORD = "Password";
	private static final String TAG_WISPR_USERNAME = "UserName";

	private static final String[] VALID_SUFFIX = { ".fon.com", ".btopenzone.com", ".btfon.com", ".neuf.fr", ".wifi.sfr.fr", ".hotspotsvankpn.com", ".livedoor.com" };

	private static LoginResult fonLogin(final String user, final String password) {
		int responseCode = ResponseCodes.WISPR_RESPONSE_CODE_ACCESS_GATEWAY_INTERNAL_ERROR;
		String replyMessage = null;
		String logoffUrl = null;
		String content = HttpUtils.getUrl(LoginManager.CONNECTION_TEST_URL);
		if (content != null) {
			if (!content.equals(LoginManager.CONNECTED)) {
				content = LoginManager.getFonXML(content);
				if (content != null) {
					final FonInfoHandler wih = new FonInfoHandler();
					if (LoginManager.parseFonXML(content, wih) && (wih.getMessageType() == ResponseCodes.WISPR_MESSAGE_TYPE_INITIAL_REDIRECT) && (wih.getResponseCode() == ResponseCodes.WISPR_RESPONSE_CODE_NO_ERROR)) {
						content = LoginManager.getFonXMLByPost(wih.getLoginURL(), user, password);
						if (content != null) {
							final FonResponseHandler wrh = new FonResponseHandler();
							if (LoginManager.parseFonXML(content, wrh)) {
								responseCode = wrh.getResponseCode();
								if (responseCode == ResponseCodes.WISPR_RESPONSE_CODE_LOGIN_SUCCEEDED) {
									logoffUrl = wrh.getLogoffURL();
								} else if (responseCode == ResponseCodes.WISPR_RESPONSE_CODE_LOGIN_FAILED) {
									responseCode = wrh.getFonResponseCode();
									replyMessage = wrh.getReplyMessage();
								}
							}
						} else if (LoginManager.isConnected()) {
							responseCode = ResponseCodes.WISPR_RESPONSE_CODE_LOGIN_SUCCEEDED;
							logoffUrl = LoginManager.DEFAULT_LOGOFF_URL;
						}
					}
				} else {
					responseCode = ResponseCodes.CUST_WISPR_NOT_PRESENT;
				}
			} else {
				responseCode = ResponseCodes.CUST_ALREADY_CONNECTED;
				logoffUrl = LoginManager.DEFAULT_LOGOFF_URL;
			}
		}
		return new LoginResult(responseCode, replyMessage, logoffUrl);
	}

	private static String getFonXML(final String source) {
		final int start = source.indexOf("<" + LoginManager.TAG_WISPR);
		final int end = source.indexOf("</" + LoginManager.TAG_WISPR + ">", start);
		if ((start == -1) || (end == -1)) {
			return null;
		}
		final String res = new String(source.substring(start, end + LoginManager.TAG_WISPR.length() + 3));
		if (!res.contains("&amp;")) {
			return res.replace("&", "&amp;");
		}
		return res;
	}

	private static String getFonXMLByPost(final String url, final String user, final String password) {
		final URL u = LoginManager.parseURL(url);
		if (u != null) {
			final ArrayList<BasicNameValuePair> p = new ArrayList<BasicNameValuePair>();
			final String username;
			if (LoginManager.isFonWISPrURL(u)) {
				username = LoginManager.FON_USERNAME_PREFIX + user;
			} else {
				username = user;
			}
			p.add(new BasicNameValuePair(LoginManager.TAG_WISPR_USERNAME, username));
			p.add(new BasicNameValuePair(LoginManager.TAG_WISPR_PASSWORD, password));
			final String r = HttpUtils.getUrlByPost(url, p);
			if (r != null) {
				return LoginManager.getFonXML(r);
			}
		}
		return null;
	}

	private static ArrayList<BasicNameValuePair> getLivedoorLoginParameters(final String user, final String password) {
		final ArrayList<BasicNameValuePair> p = new ArrayList<BasicNameValuePair>();
		final String sn;
		final String res = HttpUtils.getUrl(LoginManager.CONNECTION_TEST_URL);
		if (res == null) {
			sn = "001";
		} else {
			sn = new String(res.substring(res.indexOf("name=\"sn\" value=\"") + 17, res.indexOf("\"", res.indexOf("name=\"sn\" value=\"") + 17)));
		}
		p.add(new BasicNameValuePair("sn", sn));
		p.add(new BasicNameValuePair("original_url", LoginManager.CONNECTION_TEST_URL));
		p.add(new BasicNameValuePair("name", user + "@fon"));
		p.add(new BasicNameValuePair("password", password));
		// Click coordinates on image button, really not needed
		p.add(new BasicNameValuePair("x", "66"));
		p.add(new BasicNameValuePair("y", "15"));
		return p;
	}

	private static String getPassword(final Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getString(context.getString(R.string.key_password), "").trim();
	}

	private static String getSFRFonURL(final String source) {
		final int start = source.indexOf("SFRLoginURL_JIL");
		final int end = source.indexOf("-->", start);
		if ((start == -1) || (end == -1)) {
			return null;
		}
		final String url = source.substring(start, end);
		return new String(url.substring(url.indexOf("https")).replace("&amp;", "&").replace("notyet", "smartclient"));

	}

	private static String getUsername(final Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getString(context.getString(R.string.key_username), "").trim();
	}

	private static boolean isBT(final String ssid) {
		return ssid.equals("BTWiFi") || ssid.equals("BTWiFi-with-FON") || ssid.equals("BTOpenzone-H") || ssid.equals("BTFON");
	}

	private static boolean isConnected() {
		final String response = HttpUtils.getUrl(LoginManager.CONNECTION_TEST_URL);
		return (response != null) && response.equals(LoginManager.CONNECTED);
	}

	private static boolean isDowntownBrooklyn(final String ssid) {
		return ssid.equalsIgnoreCase("DowntownBrooklynWifi_Fon");
	}

	private static boolean isDT(final String ssid) {
		return ssid.equals("Telekom_FON");
	}

	private static boolean isFonera(final String ssid, final String bssid) {
		return !LoginManager.isLivedoor(ssid, bssid) && ssid.startsWith("FON_");
	}

	private static boolean isFonNetwork(final String ssid, final String bssid) {
		return LoginManager.isNOS(ssid) || LoginManager.isFonera(ssid, bssid) || LoginManager.isBT(ssid) || LoginManager.isProximus(ssid) || LoginManager.isKPN(ssid) || LoginManager.isDT(ssid) || LoginManager.isST(ssid) || LoginManager.isJT(ssid) || LoginManager.isHT(ssid) || LoginManager.isOTE(ssid) || LoginManager.isNETIA(ssid) || LoginManager.isRomtelecom(ssid) || LoginManager.isTTNET(ssid) || LoginManager.isOtherFon(ssid) || LoginManager.isOi(ssid) || LoginManager.isDowntownBrooklyn(ssid) || LoginManager.isMWEB(ssid) || LoginManager.isTelstra(ssid);
	}

	private static boolean isFonWISPrURL(final URL url) {
		return (url.getHost().contains("portal.fon.com") || url.getHost().contentEquals("www.btopenzone.com") || url.getHost().contains("wifi.sfr.fr")) && !(url.getHost().contains("belgacom") || url.getHost().contains("telekom"));
	}

	private static boolean isHT(final String ssid) {
		return ssid.equals("HotSpot Fon");
	}

	private static boolean isJT(final String ssid) {
		return ssid.equalsIgnoreCase("JT Fon");
	}

	private static boolean isKPN(final String ssid) {
		return ssid.equals("KPN Fon");
	}

	private static boolean isLivedoor(final String ssid, final String bssid) {
		return ((bssid == null) || !bssid.startsWith(LoginManager.FON_MAC_PREFIX)) && ssid.equalsIgnoreCase("FON_livedoor");
	}

	private static boolean isMWEB(final String ssid) {
		return ssid.equals("@MWEB FON");
	}

	private static boolean isNETIA(final String ssid) {
		return ssid.equals("FON_NETIA_FREE_INTERNET");
	}

	private static boolean isNOS(final String ssid) {
		return ssid.equals("FON_ZON_FREE_INTERNET");
	}

	private static boolean isOi(final String ssid) {
		return ssid.equals("Oi WiFi Fon") || ssid.startsWith("OI_WIFI_FON");
	}

	private static boolean isOTE(final String ssid) {
		return ssid.equals("OTE WiFi Fon");
	}

	private static boolean isOtherFon(final String ssid) {
		return ssid.equalsIgnoreCase("Fon WiFi") || ssid.equalsIgnoreCase("Fon WiFi 5GHz") || ssid.equalsIgnoreCase("Fon WiFi 5G") || ssid.equalsIgnoreCase("Fon Free WiFi") || ssid.equalsIgnoreCase("Fon WiFi (free)");
	}

	private static boolean isProximus(final String ssid) {
		return ssid.equals("PROXIMUS_FON") || ssid.equals("FON_BELGACOM");
	}

	private static boolean isRomtelecom(final String ssid) {
		return ssid.equals("Romtelecom Fon");
	}

	private static boolean isSFR(final String ssid) {
		return ssid.equals("SFR WiFi FON");
	}

	private static boolean isSoftBank(final String ssid) {
		return ssid.equalsIgnoreCase("NOC_SOFTBANK") || ssid.equals("FON");
	}

	private static boolean isST(final String ssid) {
		return ssid.equalsIgnoreCase("Telekom FON");
	}

	private static boolean isTelstra(final String ssid) {
		return ssid.equalsIgnoreCase("Telstra Air");
	}

	private static boolean isTTNET(final String ssid) {
		return ssid.equalsIgnoreCase("TTNET WiFi FON");
	}

	private static LoginResult livedoorLogin(final String user, final String password) {
		int res = ResponseCodes.WISPR_RESPONSE_CODE_ACCESS_GATEWAY_INTERNAL_ERROR;
		if (!LoginManager.isConnected()) {
			HttpUtils.getUrlByPost(LoginManager.LIVEDOOR_TARGET_URL, LoginManager.getLivedoorLoginParameters(user, password));
			if (LoginManager.isConnected()) {
				res = ResponseCodes.WISPR_RESPONSE_CODE_LOGIN_SUCCEEDED;
			}
		} else {
			res = ResponseCodes.CUST_ALREADY_CONNECTED;
		}
		return new LoginResult(res, null, null);
	}

	private static boolean parseFonXML(final String xml, final ContentHandler handler) {
		try {
			Xml.parse(xml, handler);
		} catch (final SAXException e) {
			return false;
		}
		return true;
	}

	private static URL parseURL(final String url) {
		final URL u;
		try {
			u = new URL(url);
		} catch (final MalformedURLException e) {
			return null;
		}
		if (u.getProtocol().equals("https")) {
			for (final String s : LoginManager.VALID_SUFFIX) {
				if (u.getHost().toLowerCase(Locale.US).endsWith(s)) {
					return u;
				}
			}
		}
		return null;
	}

	private static LoginResult sfrLogin(final String user, final String password) {
		int responseCode = ResponseCodes.WISPR_RESPONSE_CODE_ACCESS_GATEWAY_INTERNAL_ERROR;
		String logoffUrl = null;
		String content = HttpUtils.getUrl(LoginManager.CONNECTION_TEST_URL);
		if (content != null) {
			if (!content.equals(LoginManager.CONNECTED)) {
				content = LoginManager.getSFRFonURL(content);
				if (content != null) {
					content = LoginManager.getFonXMLByPost(content, user, password);
					if (content != null) {
						FonResponseHandler wrh = new FonResponseHandler();
						if (LoginManager.parseFonXML(content, wrh)) {
							if (wrh.getResponseCode() == ResponseCodes.WISPR_RESPONSE_CODE_AUTH_PENDING) {
								content = HttpUtils.getUrl(wrh.getLoginResultsURL());
								if (content != null) {
									wrh = new FonResponseHandler();
									if (LoginManager.parseFonXML(content, wrh)) {
										responseCode = wrh.getResponseCode();
										logoffUrl = wrh.getLogoffURL();
									}
								}
							} else {
								responseCode = wrh.getResponseCode();
								logoffUrl = wrh.getLogoffURL();
							}
						}
					} else if (LoginManager.isConnected()) {
						responseCode = ResponseCodes.WISPR_RESPONSE_CODE_LOGIN_SUCCEEDED;
						logoffUrl = LoginManager.DEFAULT_LOGOFF_URL;
					}
				} else {
					responseCode = ResponseCodes.CUST_WISPR_NOT_PRESENT;
				}
			} else {
				responseCode = ResponseCodes.CUST_ALREADY_CONNECTED;
				logoffUrl = LoginManager.DEFAULT_LOGOFF_URL;
			}
		}
		return new LoginResult(responseCode, null, logoffUrl);
	}

	public static boolean isSupportedNetwork(final String ssid, final String bssid) {
		return LoginManager.isFonNetwork(ssid, bssid) || LoginManager.isSFR(ssid) || LoginManager.isSoftBank(ssid) || LoginManager.isLivedoor(ssid, bssid);
	}

	public static LoginResult login(final Context context, final String ssid, final String bssid) {
		final String user = LoginManager.getUsername(context);
		final String password = LoginManager.getPassword(context);
		final LoginResult r;
		if ((user.length() == 0) || (password.length() == 0)) {
			r = new LoginResult(ResponseCodes.CUST_CREDENTIALS_ERROR, null, null);
		} else if (LoginManager.isLivedoor(ssid, bssid)) {
			r = LoginManager.livedoorLogin(user, password);
		} else if (LoginManager.isSFR(ssid)) {
			r = LoginManager.sfrLogin(user, password);
		} else {
			r = LoginManager.fonLogin(user, password);
		}
		return r;
	}

}

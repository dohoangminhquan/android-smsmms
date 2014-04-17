package com.klinker.android.send_message;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.XmlResourceParser;
import android.preference.PreferenceManager;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.telephony.cdma.CdmaCellLocation;
import android.util.Log;
import android.widget.Toast;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;

public class ApnUtils {

    private static final String TAG = "ApnUtils";

    public static void initDefaultApns(final Context context, final OnApnFinishedListener listener) {
        loadMmsSettings(context);
        final ArrayList<APN> apns = loadApns(context);

        if (apns.size() == 0) {
            Log.v(TAG, "Found no APNs :( Damn CDMA network probably.");
        } else if (apns.size() == 1) {
            setApns(context, apns.get(0));
            if (listener != null) {
                listener.onFinished();
            }
        } else {
            if (!PreferenceManager.getDefaultSharedPreferences(context).getBoolean("has_seen_select_apns_warning", false)) {
                new AlertDialog.Builder(context)
                        .setTitle(R.string.auto_select_apn)
                        .setMessage(R.string.auto_select_multiple_apns)
                        .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int num) {
                                showApnChooser(context, apns, listener);
                            }
                        })
                        .show();
                PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean("has_seen_select_apns_warning", true).commit();
            } else {
                showApnChooser(context, apns, listener);
            }
        }
    }

    private static void showApnChooser(final Context context, final ArrayList<APN> apns, final OnApnFinishedListener listener) {
        CharSequence[] items = new CharSequence[apns.size()];
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        String curMmsc = sharedPrefs.getString("mmsc_url", "");
        String curProxy = sharedPrefs.getString("mms_proxy", "");
        String curPort = sharedPrefs.getString("mms_port", "");

        int defaultApn = -1;
        for (int i = 0; i < items.length; i++) {
            APN apn = apns.get(i);
            items[i] = (i+1) + ". " + apn.name;

            if (apn.mmsc.equals(curMmsc) && apn.proxy.equals(curProxy) && apn.port.equals(curPort)) {
                defaultApn = i;
            }
        }

        new AlertDialog.Builder(context)
                .setSingleChoiceItems(items, defaultApn, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        setApns(context, apns.get(i));
                        if (listener != null) {
                            listener.onFinished();
                        }
                        dialogInterface.dismiss();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialogInterface) {
                        if (listener != null) {
                            listener.onFinished();
                        }
                    }
                })
                .show();
    }

    public interface OnApnFinishedListener {
        public abstract void onFinished();
    }

    private static void loadMmsSettings(Context context) {
        XmlResourceParser parser = context.getResources().getXml(R.xml.mms_config);
        String maxMessageSize = 1000000 + "";
        int maxImageHeight = 800;
        int maxImageWidth = 800;
        String userAgent = "Android-Mms/2.0";
        String uaProfUrl = "";

        String mmsc = "";
        String mmsproxy = "";
        String mmsport = "";

        try {
            beginDocument(parser, "mms_config");

            while (true) {
                nextElement(parser);
                String tag = parser.getName();
                if (tag == null) {
                    break;
                }
                String name = parser.getAttributeName(0);
                String value = parser.getAttributeValue(0);
                String text = null;
                if (parser.next() == XmlPullParser.TEXT) {
                    text = parser.getText();
                }

                Log.v(TAG, "tag: " + tag + " value: " + value + " - " +
                        text);
                if ("name".equalsIgnoreCase(name)) {
                    if ("int".equals(tag)) {
                        // int config tags go here
                        if ("maxMessageSize".equalsIgnoreCase(value)) {
                            maxMessageSize = text;
                        } else if ("maxImageHeight".equalsIgnoreCase(value)) {
                            maxImageHeight = Integer.parseInt(text);
                        } else if ("maxImageWidth".equalsIgnoreCase(value)) {
                            maxImageWidth = Integer.parseInt(text);
                        }
                    } else if ("string".equals(tag)) {
                        // string config tags go here
                        if ("userAgent".equalsIgnoreCase(value)) {
                            userAgent = text;
                        } else if ("uaProfUrl".equalsIgnoreCase(value)) {
                            uaProfUrl = text;
                        } else if ("mmsc".equals(value)) {
                            mmsc = text.trim();
                        } else if ("mmsproxy".equals(value)) {
                            mmsproxy = text.trim();
                        } else if ("mmsport".equals(value)) {
                            mmsport = text.trim();
                        }
                    }
                }
            }
        } catch (XmlPullParserException e) {
            Log.e(TAG, "loadMmsSettings caught ", e);
        } catch (NumberFormatException e) {
            Log.e(TAG, "loadMmsSettings caught ", e);
        } catch (IOException e) {
            Log.e(TAG, "loadMmsSettings caught ", e);
        } finally {
            parser.close();
        }

        String errorStr = null;

        if (errorStr != null) {
            String err =
                    String.format("MmsConfig.loadMmsSettings mms_config.xml missing %s setting",
                            errorStr);
            Log.e(TAG, err);
        }

        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putInt("mms_max_width", maxImageWidth)
                .putInt("mms_max_height", maxImageHeight)
                .putString("mms_max_size", maxMessageSize)
                .putString("mms_agent", userAgent)
                .putString("mms_user_agent_profile_url", uaProfUrl)
                .commit();

        if (!mmsc.equals("")) {
            APN apn = new APN();
            apn.mmsc = mmsc;
            apn.proxy = mmsproxy;
            apn.port = mmsport;
            setApns(context, apn);
        }
    }

    private static ArrayList<APN> loadApns(Context context) {
        XmlResourceParser parser = context.getResources().getXml(R.xml.apns);
        ArrayList<APN> apns = new ArrayList<APN>();
        String mmsc = "", proxy = "", port = "", carrier = "";

        String mcc, mnc;

        TelephonyManager manager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        String networkOperator = manager.getNetworkOperator();

        if (networkOperator != null) {
            mcc = networkOperator.substring(0, 3);
            String s = networkOperator.substring(3);
            mnc = s.replaceFirst("^0{1,2}", "");
        } else {
            mcc = context.getResources().getConfiguration().mcc + "";
            mnc = context.getResources().getConfiguration().mnc + "";
        }

        if (mcc.equals("")) {
            mcc = new ServiceState().getOperatorNumeric().substring(0, 3);
        }

        if (mnc.equals("")) {
            TelephonyManager tm  = (TelephonyManager) context.getSystemService
                    (Context.TELEPHONY_SERVICE);
            mnc = ((CdmaCellLocation) tm.getCellLocation()).getSystemId() + "";
        }

        if (mcc.trim().equals("") || mnc.trim().equals("")) {
            Log.v(TAG, "couldn't find both mcc and mnc. mcc = " + mcc + ", mnc = " + mnc);
            return null;
        }

        Log.v(TAG, "mcc: " + mcc + " mnc: " + mnc);

        try {
            beginDocument(parser, "apns");

            while (true) {
                nextElement(parser);
                String tag = parser.getName();
                if (tag == null) {
                    break;
                }

                boolean mccCorrect = false, mncCorrect = false;
                for (int i = 0; i < parser.getAttributeCount(); i++) {
                    String name = parser.getAttributeName(i);
                    String value = parser.getAttributeValue(i);
                    if ("mcc".equals(name) && mcc.equals(value)) {
                        mccCorrect = true;
                    } else if ("mnc".equals(name) && mnc.equalsIgnoreCase(value)) {
                        mncCorrect = true;
                    }
                }

                if (mccCorrect && mncCorrect) {
                    // parse the rest of the apn
                    for (int i = 0; i < parser.getAttributeCount(); i++) {
                        String name = parser.getAttributeName(i);
                        String value = parser.getAttributeValue(i);
                        if ("type".equals(name)) {
                            if (!value.contains("mms")) {
                                mmsc = "";
                                proxy = "";
                                port = "";
                                break;
                            }
                        } else if ("mmsc".equals(name)) {
                            mmsc = value;
                        } else if ("mmsproxy".equals(name)) {
                            proxy = value;
                        } else if ("mmsport".equals(name)) {
                            port = value;
                        } else if ("carrier".equals(name)) {
                            carrier = value;
                        } else if ("port".equals(name) && port.equals("")) {
                            port = value;
                        }
                    }

                    if (!mmsc.equals("")) {
                        APN apn = new APN();
                        apn.name = carrier;
                        apn.mmsc = mmsc;
                        apn.proxy = proxy;
                        apn.port = port;
                        apns.add(apn);
                    }
                }
            }
        } catch (XmlPullParserException e) {
            Log.e(TAG, "loadApns caught ", e);
        } catch (NumberFormatException e) {
            Log.e(TAG, "loadApns caught ", e);
        } catch (IOException e) {
            Log.e(TAG, "loadApns caught ", e);
        } finally {
            parser.close();
        }

        Log.v(TAG, "MMSC: " + mmsc + ", MMS Proxy: " + proxy + ", MMS Port: " + port);

        String errorStr = null;

        if (errorStr != null) {
            String err =
                    String.format("MmsConfig.loadMmsSettings mms_config.xml missing %s setting",
                            errorStr);
            Log.e(TAG, err);
        }

        return apns;
    }

    private static void setApns(Context context, APN apn) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString("mmsc_url", apn.mmsc)
                .putString("mms_proxy", apn.proxy)
                .putString("mms_port", apn.port)
                .commit();
    }

    private static void beginDocument(XmlPullParser parser, String firstElementName) throws XmlPullParserException, IOException {
        int type;
        while ((type=parser.next()) != parser.START_TAG
                && type != parser.END_DOCUMENT) {
            ;
        }

        if (type != parser.START_TAG) {
            throw new XmlPullParserException("No start tag found");
        }

        if (!parser.getName().equals(firstElementName)) {
            throw new XmlPullParserException("Unexpected start tag: found " + parser.getName() +
                    ", expected " + firstElementName);
        }
    }

    private static void nextElement(XmlPullParser parser) throws XmlPullParserException, IOException {
        int type;
        while ((type=parser.next()) != parser.START_TAG
                && type != parser.END_DOCUMENT) {
            ;
        }
    }

    private static class APN {
        public String name;
        public String mmsc;
        public String proxy;
        public String port;
    }
}

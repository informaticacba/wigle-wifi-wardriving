package net.wigle.wigleandroid;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.method.PasswordTransformationMethod;
import android.text.method.SingleLineTransformationMethod;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import net.wigle.wigleandroid.background.ApiDownloader;
import net.wigle.wigleandroid.background.DownloadHandler;
import net.wigle.wigleandroid.listener.GPSListener;
import net.wigle.wigleandroid.listener.PrefCheckboxListener;
import net.wigle.wigleandroid.util.FileUtility;
import net.wigle.wigleandroid.util.Logging;
import net.wigle.wigleandroid.util.SettingsUtil;

import static net.wigle.wigleandroid.UserStatsFragment.MSG_USER_DONE;

/**
 * configure settings
 */
public final class SettingsFragment extends Fragment implements DialogListener {

    private static final int MENU_ERROR_REPORT = 13;
    private static final int MENU_DEBUG = 14;
    private static final int DONATE_DIALOG=112;
    private static final int ANONYMOUS_DIALOG=113;
    private static final int DEAUTHORIZE_DIALOG=114;

    public boolean allowRefresh = false;

    /** convenience, just get the darn new string */
    public static abstract class SetWatcher implements TextWatcher {
        @Override
        public void afterTextChanged( final Editable s ) {}
        @Override
        public void beforeTextChanged( final CharSequence s, final int start, final int count, final int after ) {}
        @Override
        public void onTextChanged( final CharSequence s, final int start, final int before, final int count ) {
            onTextChanged( s.toString() );
        }
        public abstract void onTextChanged( String s );
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate( final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // set language
        MainActivity.setLocale(getActivity());
        setHasOptionsMenu(true);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.settings, container, false);

        // force media volume controls
        getActivity().setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // don't let the textbox have focus to start with, so we don't see a keyboard right away
        final LinearLayout linearLayout = (LinearLayout) view.findViewById(R.id.linearlayout);
        linearLayout.setFocusableInTouchMode(true);
        linearLayout.requestFocus();
        updateView(view);
        return view;
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void handleDialog(final int dialogId) {
        final SharedPreferences prefs = getActivity().getSharedPreferences(ListFragment.SHARED_PREFS, 0);
        final Editor editor = prefs.edit();
        final View view = getView();

        switch (dialogId) {
            case DONATE_DIALOG: {
                editor.putBoolean(ListFragment.PREF_DONATE, true);
                editor.apply();

                if (view != null) {
                    final CheckBox donate = (CheckBox) view.findViewById(R.id.donate);
                    donate.setChecked(true);
                }
                // poof
                eraseDonate();
                break;
            }
            case ANONYMOUS_DIALOG: {
                // turn anonymous
                editor.putBoolean( ListFragment.PREF_BE_ANONYMOUS, true );
                editor.remove(ListFragment.PREF_USERNAME);
                editor.remove(ListFragment.PREF_PASSWORD);
                editor.remove(ListFragment.PREF_AUTHNAME);
                editor.remove(ListFragment.PREF_TOKEN);
                editor.apply();

                if (view != null) {
                    this.updateView(view);
                }
                break;
            }
            case DEAUTHORIZE_DIALOG: {
                editor.remove(ListFragment.PREF_AUTHNAME);
                editor.remove(ListFragment.PREF_TOKEN);
                editor.remove(ListFragment.PREF_CONFIRM_UPLOAD_USER);

                String mapTileMode = prefs.getString(ListFragment.PREF_SHOW_DISCOVERED,
                        ListFragment.PREF_MAP_NO_TILE);
                if (ListFragment.PREF_MAP_NOTMINE_TILE.equals(mapTileMode) ||
                        ListFragment.PREF_MAP_ONLYMINE_TILE.equals(mapTileMode)) {
                    // ALIBI: clear show mine/others on deauthorize
                    editor.putString(ListFragment.PREF_SHOW_DISCOVERED, ListFragment.PREF_MAP_NO_TILE);
                }
                editor.apply();
                if (view != null) {
                    this.updateView(view);
                }
                break;
            }
            default:
                Logging.warn("Settings unhandled dialogId: " + dialogId);
        }
    }

    @Override
    public void onResume() {
        Logging.info("resume settings.");

        final SharedPreferences prefs = getActivity().getSharedPreferences(ListFragment.SHARED_PREFS, 0);
        // donate
        final boolean isDonate = prefs.getBoolean(ListFragment.PREF_DONATE, false);
        if ( isDonate ) {
            eraseDonate();
        }
        super.onResume();
        Logging.info("Resume with allow: "+allowRefresh);
        if (allowRefresh) {
            allowRefresh = false;
            final View view = getView();

            updateView(view);

            //ALIBI: what doesn't work here:
            //does not successfully reload
            //getFragmentManager().beginTransaction().replace(this.container.getId(),this).commit();

            // WTF: actually re-pauses and resumes.
            //getFragmentManager().beginTransaction().detach(this).attach(this).commit();
        }
        getActivity().setTitle(R.string.settings_app_name);
    }

    @Override
    public void onPause() {
        super.onPause();
        Logging.info("Pause; setting allowRefresh");
        allowRefresh = true;
    }

    private void updateView(final View view) {
        final FragmentActivity activity = getActivity();
        if (activity == null) return;
        final SharedPreferences prefs = activity.getSharedPreferences(ListFragment.SHARED_PREFS, 0);
        final Editor editor = prefs.edit();

        // donate
        final CheckBox donate = (CheckBox) view.findViewById(R.id.donate);
        final boolean isDonate = prefs.getBoolean( ListFragment.PREF_DONATE, false);
        donate.setChecked( isDonate );
        if ( isDonate ) {
            eraseDonate();
        }
        donate.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged( final CompoundButton buttonView, final boolean isChecked ) {
                if ( isChecked == prefs.getBoolean( ListFragment.PREF_DONATE, false) ) {
                    // this would cause no change, bail
                    return;
                }

                if ( isChecked ) {
                    // turn off until confirmed
                    buttonView.setChecked( false );
                    // confirm
                    MainActivity.createConfirmation( getActivity(),
                            getString(R.string.donate_question) + "\n\n"
                                    + getString(R.string.donate_explain),
                            R.id.nav_settings, DONATE_DIALOG);
                }
                else {
                    editor.putBoolean( ListFragment.PREF_DONATE, false);
                    editor.apply();
                }
            }
        });

        final TextView scanThrottleHelp = view.findViewById(R.id.scan_throttle_help);
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
            scanThrottleHelp.setText(R.string.pie_bad);
            scanThrottleHelp.setVisibility(View.VISIBLE);
        }  else if (Build.VERSION.SDK_INT == 29) {
            final StringBuilder builder = new StringBuilder(getString(R.string.q_bad));
            addDevModeMesgIfApplicable(builder, getContext(), getString(R.string.enable_developer));
            builder.append(getString(R.string.disable_throttle));
            scanThrottleHelp.setText(builder.toString());
            scanThrottleHelp.setVisibility(View.VISIBLE);
        } else if (Build.VERSION.SDK_INT > 29) {
            //ALIBI: starting in SDK 30, we can check the throttle via WiFiManager.isScanThrottleEnabled
            final Context mainActivity = MainActivity.getMainActivity();
            final WifiManager wifiManager = (WifiManager) mainActivity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager.isScanThrottleEnabled()) {
                final StringBuilder builder = new StringBuilder(getString(R.string.throttle));
                addDevModeMesgIfApplicable(builder, getContext(), getString(R.string.enable_developer));
                scanThrottleHelp.setText(builder.toString());
                scanThrottleHelp.setVisibility(View.VISIBLE);
            }
        }

        final String authUser = prefs.getString(ListFragment.PREF_AUTHNAME,"");
        final EditText user = (EditText) view.findViewById(R.id.edit_username);
        final TextView authUserDisplay = (TextView) view.findViewById(R.id.show_authuser);
        final View authUserLayout = view.findViewById(R.id.show_authuser_label);
        final EditText passEdit = (EditText) view.findViewById(R.id.edit_password);
        final View passEditLayout = view.findViewById(R.id.edit_password_label);
        final CheckBox showPass = (CheckBox) view.findViewById(R.id.showpassword);
        final String authToken = prefs.getString(ListFragment.PREF_TOKEN, "");
        final Button deauthButton = (Button) view.findViewById(R.id.deauthorize_client);
        final Button authButton = (Button) view.findViewById(R.id.authorize_client);

        if (!authUser.isEmpty()) {
            authUserDisplay.setText(authUser);
            authUserDisplay.setVisibility(View.VISIBLE);
            authUserLayout.setVisibility(View.VISIBLE);
            if (!authToken.isEmpty()) {
                deauthButton.setVisibility(View.VISIBLE);
                deauthButton.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        MainActivity.createConfirmation( getActivity(),
                                getString(R.string.deauthorize_confirm),
                                R.id.nav_settings, DEAUTHORIZE_DIALOG );
                    }
                });
                authButton.setVisibility(View.GONE);
                passEdit.setVisibility(View.GONE);
                passEditLayout.setVisibility(View.GONE);
                showPass.setVisibility(View.GONE);
                user.setEnabled(false);
            } else {
                user.setEnabled(true);
            }
        } else {
            user.setEnabled(true);
            authUserDisplay.setVisibility(View.GONE);
            authUserLayout.setVisibility(View.GONE);
            deauthButton.setVisibility(View.GONE);
            passEdit.setVisibility(View.VISIBLE);
            passEditLayout.setVisibility(View.VISIBLE);
            showPass.setVisibility(View.VISIBLE);
            authButton.setVisibility(View.VISIBLE);
            final Handler handler = new UserDownloadHandler(view, getActivity().getPackageName(),
                    getResources(), this);
            final UserStatsFragment.UserDownloadApiListener apiListener =
                    new UserStatsFragment.UserDownloadApiListener(handler);
            authButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View view) {
                    final ApiDownloader task = new ApiDownloader(getActivity(), ListFragment.lameStatic.dbHelper,
                            "user-stats-cache.json", MainActivity.USER_STATS_URL, false, true, true,
                            ApiDownloader.REQUEST_GET,
                            apiListener);
                    try {
                        task.startDownload(SettingsFragment.this);
                    } catch (WiGLEAuthException waex) {
                        Logging.info("User authentication failed");
                    }
                }
            });
        }

        // anonymous
        final CheckBox beAnonymous = (CheckBox) view.findViewById(R.id.be_anonymous);
        final boolean isAnonymous = prefs.getBoolean( ListFragment.PREF_BE_ANONYMOUS, false);
        if ( isAnonymous ) {
            user.setEnabled( false );
            passEdit.setEnabled( false );
        }

        beAnonymous.setChecked( isAnonymous );
        beAnonymous.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged( final CompoundButton buttonView, final boolean isChecked ) {
                if ( isChecked == prefs.getBoolean(ListFragment.PREF_BE_ANONYMOUS, false) ) {
                    // this would cause no change, bail
                    return;
                }

                if ( isChecked ) {
                    // turn off until confirmed
                    buttonView.setChecked( false );
                    // confirm
                    MainActivity.createConfirmation( getActivity(),
                            getString(R.string.anonymous_confirm), R.id.nav_settings,
                            ANONYMOUS_DIALOG );
                } else {
                    // unset anonymous
                    user.setEnabled(true);
                    passEdit.setEnabled(true);
                    editor.putBoolean( ListFragment.PREF_BE_ANONYMOUS, false );
                    editor.apply();

                    // might have to remove or show register link
                    updateRegister(view);
                }
            }
        });

        // register link
        final TextView register = (TextView) view.findViewById(R.id.register);
        final String registerString = getString(R.string.register);
        final String activateString = getString(R.string.activate);
        String registerBlurb = "<a href='net.wigle.wigleandroid.register://register'>" + registerString +
                "</a> @WiGLE.net";

        // ALIBI: vision APIs started in 4.2.2; JB2 4.3 = 18 is safe. 17 might work...
        // but we're only supporting qr in v23+ via the uses-permission-sdk-23 tag -rksh
        if (Build.VERSION.SDK_INT >= 23) {
            registerBlurb += " or <a href='net.wigle.wigleandroid://activate'>" + activateString +
                    "</a>";
        }
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                register.setText(Html.fromHtml(registerBlurb,
                        Html.FROM_HTML_MODE_LEGACY));
            } else {
                register.setText(Html.fromHtml(registerBlurb));
            }
        } catch (Exception ex) {
            register.setText(registerString + " @WiGLE.net");
        }
        register.setMovementMethod(LinkMovementMethod.getInstance());
        updateRegister(view);

        user.setText( prefs.getString( ListFragment.PREF_USERNAME, "" ) );
        user.addTextChangedListener( new SetWatcher() {
            @Override
            public void onTextChanged( final String s ) {
                credentialsUpdate(ListFragment.PREF_USERNAME, editor, prefs, s);

                // might have to remove or show register link
                updateRegister(view);
            }
        });

        final CheckBox showPassword = (CheckBox) view.findViewById(R.id.showpassword);
        showPassword.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged( final CompoundButton buttonView, final boolean isChecked ) {
                if ( isChecked ) {
                    passEdit.setTransformationMethod(SingleLineTransformationMethod.getInstance());
                }
                else {
                    passEdit.setTransformationMethod(PasswordTransformationMethod.getInstance());
                }
            }
        });
;

        final String showDiscovered = prefs.getString( ListFragment.PREF_SHOW_DISCOVERED, ListFragment.PREF_MAP_NO_TILE);
        final boolean isAuthenticated = (!authUser.isEmpty() && !authToken.isEmpty() && !isAnonymous);
        final String[] mapModes = SettingsUtil.getMapModes(isAuthenticated);
        final String[] mapModeName = SettingsUtil.getMapModeNames(isAuthenticated, this.getContext());

        if (!ListFragment.PREF_MAP_NO_TILE.equals(showDiscovered)) {
            LinearLayout mainLayout = (LinearLayout) view.findViewById(R.id.show_map_discovered_since);
            mainLayout.setVisibility(View.VISIBLE);
        }

        SettingsUtil.doMapSpinner( R.id.show_discovered, ListFragment.PREF_SHOW_DISCOVERED,
                ListFragment.PREF_MAP_NO_TILE, mapModes, mapModeName, getContext(), view );

        int thisYear = Calendar.getInstance().get(Calendar.YEAR);
        List<Long> yearValueBase = new ArrayList<Long>();
        List<String> yearLabelBase = new ArrayList<String>();
        for (int i = 2001; i <= thisYear; i++) {
            yearValueBase.add((long)(i));
            yearLabelBase.add(Integer.toString(i));
        }
        SettingsUtil.doSpinner( R.id.networks_discovered_since_year, view, ListFragment.PREF_SHOW_DISCOVERED_SINCE,
                2001L, yearValueBase.toArray(new Long[0]),
                yearLabelBase.toArray(new String[0]), getContext() );

        passEdit.setText( prefs.getString( ListFragment.PREF_PASSWORD, "" ) );
        passEdit.addTextChangedListener( new SetWatcher() {
            @Override
            public void onTextChanged( final String s ) {
                credentialsUpdate(ListFragment.PREF_PASSWORD, editor, prefs, s);
            }
        });

        final Button button = (Button) view.findViewById(R.id.speech_button);
        button.setOnClickListener( new OnClickListener() {
            @Override
            public void onClick( final View view ) {
                final Intent errorReportIntent = new Intent( getActivity(), SpeechActivity.class );
                SettingsFragment.this.startActivity( errorReportIntent );
            }
        });

        // period spinners
        SettingsUtil.doScanSpinner( R.id.periodstill_spinner, ListFragment.PREF_SCAN_PERIOD_STILL,
                MainActivity.SCAN_STILL_DEFAULT, getString(R.string.nonstop), view, getContext() );
        SettingsUtil.doScanSpinner( R.id.period_spinner, ListFragment.PREF_SCAN_PERIOD,
                MainActivity.SCAN_DEFAULT, getString(R.string.nonstop), view, getContext() );
        SettingsUtil.doScanSpinner( R.id.periodfast_spinner, ListFragment.PREF_SCAN_PERIOD_FAST,
                MainActivity.SCAN_FAST_DEFAULT, getString(R.string.nonstop), view, getContext() );
        SettingsUtil.doScanSpinner( R.id.gps_spinner, ListFragment.GPS_SCAN_PERIOD,
                MainActivity.LOCATION_UPDATE_INTERVAL, getString(R.string.setting_tie_wifi), view, getContext() );

        MainActivity.prefBackedCheckBox(this.getActivity(), view, R.id.edit_showcurrent, ListFragment.PREF_SHOW_CURRENT, true);
        MainActivity.prefBackedCheckBox(this.getActivity(), view, R.id.use_metric, ListFragment.PREF_METRIC, false);
        MainActivity.prefBackedCheckBox(this.getActivity(), view, R.id.found_sound, ListFragment.PREF_FOUND_SOUND, true);
        MainActivity.prefBackedCheckBox(this.getActivity(), view, R.id.found_new_sound, ListFragment.PREF_FOUND_NEW_SOUND, true);
        MainActivity.prefBackedCheckBox(this.getActivity(), view, R.id.circle_size_map, ListFragment.PREF_CIRCLE_SIZE_MAP, false);
        MainActivity.prefBackedCheckBox(this.getActivity(), view, R.id.no_individual_nets_map, ListFragment.PREF_MAP_HIDE_NETS, false);
        MainActivity.prefBackedCheckBox(this.getActivity(), view, R.id.use_network_location, ListFragment.PREF_USE_NETWORK_LOC, false);
        MainActivity.prefBackedCheckBox(this.getActivity(), view, R.id.disable_toast, ListFragment.PREF_DISABLE_TOAST, false);
        MainActivity.prefBackedCheckBox(this.getActivity(), view, R.id.boot_start, ListFragment.PREF_START_AT_BOOT ,false);
        MainActivity.prefBackedCheckBox(this.getActivity(), view, R.id.bluetooth_ena, ListFragment.PREF_SCAN_BT, true, new PrefCheckboxListener() {
            @Override
            public void preferenceSet(boolean value) {
                Logging.info("Signaling bluetooth change: "+value);
                if (value) {
                    MainActivity.getMainActivity().setupBluetooth();
                } else {
                    MainActivity.getMainActivity().endBluetooth(prefs);
                }
            }
        });
        MainActivity.prefBackedCheckBox(this.getActivity(), view, R.id.enable_route_map_display , ListFragment.PREF_VISUALIZE_ROUTE, false, new PrefCheckboxListener() {
            @Override
            public void preferenceSet(boolean value) {
                Logging.info("Signaling route mapping change: "+value);
                if (value) {
                    MainActivity.getMainActivity().startRouteMapping(prefs);
                } else {
                    MainActivity.getMainActivity().endRouteMapping(prefs);
                }
            }
        });
        MainActivity.prefBackedCheckBox(this.getActivity(), view, R.id.enable_route_logging, ListFragment.PREF_LOG_ROUTES, false, new PrefCheckboxListener() {
            @Override
            public void preferenceSet(boolean value) {
                Logging.info("Signaling route logging change: "+value);
                if (value) {
                    MainActivity.getMainActivity().startRouteLogging(prefs);
                } else {
                    MainActivity.getMainActivity().endRouteLogging();
                }
            }
        });
        MainActivity.prefBackedCheckBox(this.getActivity(), view, R.id.enable_map_theme , ListFragment.PREF_MAPS_FOLLOW_DAYNIGHT, false);
        final String[] languages = new String[]{ "", "en", "ar", "cs", "da", "de", "es-rES", "fi", "fr", "fy",
                "he", "hi-rIN", "hu", "it", "ja-rJP", "ko", "nl", "no", "pl", "pt-rPT", "pt-rBR", "ro-rRO", "ru", "sv",
                "sw", "tr", "zh-rCN", "zh-rTW", "zh-rHK" };
        final String[] languageName = new String[]{ getString(R.string.auto), getString(R.string.language_en),
                getString(R.string.language_ar), getString(R.string.language_cs), getString(R.string.language_da),
                getString(R.string.language_de), getString(R.string.language_es), getString(R.string.language_fi),
                getString(R.string.language_fr), getString(R.string.language_fy), getString(R.string.language_he),
                getString(R.string.language_hi), getString(R.string.language_hu), getString(R.string.language_it),
                getString(R.string.language_ja), getString(R.string.language_ko), getString(R.string.language_nl),
                getString(R.string.language_no), getString(R.string.language_pl), getString(R.string.language_pt),
                getString(R.string.language_pt_rBR), getString(R.string.language_ro_rRO), getString(R.string.language_ru),
                getString(R.string.language_sv), getString(R.string.language_sw), getString(R.string.language_tr),
                getString(R.string.language_zh_cn), getString(R.string.language_zh_tw), getString(R.string.language_zh_hk),
        };
        SettingsUtil.doSpinner( R.id.language_spinner, view, ListFragment.PREF_LANGUAGE, "", languages, languageName, getContext() );

        if (Build.VERSION.SDK_INT > 28) {
            View theme = view.findViewById(R.id.theme_section);
            theme.setVisibility(View.VISIBLE);
            final Integer[] themes = new Integer[] {AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM};
            final String[] themeName = new String[]{getString(R.string.theme_dark_label), getString(R.string.theme_light_label), getString(R.string.theme_follow_label)};
            SettingsUtil.doSpinner(R.id.theme_spinner, view, ListFragment.PREF_DAYNIGHT_MODE, AppCompatDelegate.MODE_NIGHT_YES, themes, themeName, getContext());
        }

        final String off = getString(R.string.off);
        final String sec = " " + getString(R.string.sec);
        final String min = " " + getString(R.string.min);

        // battery kill spinner
        final Long[] batteryPeriods = new Long[]{ 1L,2L,3L,4L,5L,10L,15L,20L,0L };
        final String[] batteryName = new String[]{ "1 %","2 %","3 %","4 %","5 %","10 %","15 %","20 %",off };
        SettingsUtil.doSpinner( R.id.battery_kill_spinner, view, ListFragment.PREF_BATTERY_KILL_PERCENT,
                MainActivity.DEFAULT_BATTERY_KILL_PERCENT, batteryPeriods, batteryName, getContext() );

        // reset wifi spinner
        final Long[] resetPeriods = new Long[]{ 15000L,30000L,60000L,90000L,120000L,300000L,600000L,0L };
        final String[] resetName = new String[]{ "15" + sec, "30" + sec,"1" + min,"1.5" + min,
                "2" + min,"5" + min,"10" + min,off };
        SettingsUtil.doSpinner( R.id.reset_wifi_spinner, view, ListFragment.PREF_RESET_WIFI_PERIOD,
                MainActivity.DEFAULT_RESET_WIFI_PERIOD, resetPeriods, resetName, getContext() );

        final Long[] timeoutPeriods = new Long[]{GPSListener.GPS_TIMEOUT_DEFAULT, 30000L, GPSListener.NET_LOC_TIMEOUT_DEFAULT, 300000L, 1800000L, 3600000L};
        final String[] timeoutName = new String[]{ "15" + sec, "30" + sec,"1" + min,"5" + min,
                "30" + min,"60" + min};
        // gps timeout spinner
        SettingsUtil.doSpinner( R.id.gps_timeout_spinner, view, ListFragment.PREF_GPS_TIMEOUT,
                GPSListener.GPS_TIMEOUT_DEFAULT, timeoutPeriods, timeoutName, getContext() );

        // net loc timeout spinner
        SettingsUtil.doSpinner( R.id.net_loc_timeout_spinner, view, ListFragment.PREF_NET_LOC_TIMEOUT,
                GPSListener.NET_LOC_TIMEOUT_DEFAULT, timeoutPeriods, timeoutName, getContext() );

        // prefs setting for tap-to-pause scan indicator
        final String[] pauseOptions = new String[] {ListFragment.QUICK_SCAN_UNSET, ListFragment.QUICK_SCAN_PAUSE, ListFragment.QUICK_SCAN_DO_NOTHING};
        final String[] pauseOptionNames = new String[] {getString(R.string.quick_pause_unset), getString(R.string.quick_pause), getString(R.string.quick_pause_do_nothing)};
        SettingsUtil.doSpinner( R.id.quick_pause_spinner, view, ListFragment.PREF_QUICK_PAUSE,
                ListFragment.QUICK_SCAN_UNSET, pauseOptions, pauseOptionNames, getContext() );

        MainActivity.prefBackedCheckBox(this.getActivity(), view, R.id.enable_kalman, ListFragment.PREF_GPS_KALMAN_FILTER ,true);

        TextView appVersion = view.findViewById(R.id.app_version);
        final String appName = getString(R.string.app_name);
        if (null != appVersion) {
            try {
                String versionName = activity.getApplicationContext().getPackageManager().getPackageInfo(activity.getApplicationContext().getPackageName(), 0).versionName;
                appVersion.setText(appName+" v."+versionName);
            } catch (PackageManager.NameNotFoundException e) {
                Logging.error("Unable to get version number: ",e);
            }
        }
    }

    private void updateRegister(final View view) {
        final SharedPreferences prefs = getActivity().getSharedPreferences(ListFragment.SHARED_PREFS, 0);
        final String username = prefs.getString(ListFragment.PREF_USERNAME, "");
        final boolean isAnonymous = prefs.getBoolean(ListFragment.PREF_BE_ANONYMOUS, false);

        if (view != null) {
            final TextView register = (TextView) view.findViewById(R.id.register);

            //ALIBI: ActivateAcitivity.receiveDetections sets isAnonymous = false
            if ("".equals(username) || isAnonymous) {
                register.setEnabled(true);
                register.setVisibility(View.VISIBLE);
            } else {
                // poof
                register.setEnabled(false);
                register.setVisibility(View.GONE);
            }
        }
    }

    /**
     * The little dance we do when we update username or password, removing old creds/cache
     * @param key the ListFragment key (u or p)
     * @param editor prefs editor reference
     * @param prefs preferences for checks
     * @param newValue the new value for the username or pass
     */
    public void credentialsUpdate(String key, Editor editor, SharedPreferences prefs, String newValue) {
        //DEBUG: MainActivity.info(key + ": " + newValue.trim());
        String currentValue = prefs.getString(key, "");
        if (currentValue.equals(newValue.trim())) {
            return;
        }
        if (newValue.trim().isEmpty()) {
            //ALIBI: empty values should unset
            editor.remove(key);
        } else {
            editor.putString(key, newValue.trim());
        }
        // ALIBI: if the u|p changes, force refetch token
        editor.remove(ListFragment.PREF_AUTHNAME);
        editor.remove(ListFragment.PREF_TOKEN);
        editor.remove(ListFragment.PREF_CONFIRM_UPLOAD_USER);
        editor.apply();
        this.clearCachefiles();
    }


    /**
     * clear cache files (i.e. on creds change)
     */
    private void clearCachefiles() {
        final File cacheDir = new File(FileUtility.getSDPath());
        final File[] cacheFiles = cacheDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept( final File dir,
                                   final String name ) {
                return name.matches( ".*-cache\\.json" );
            }
        } );
        if (null != cacheFiles) {
            for (File cache: cacheFiles) {
                //DEBUG: MainActivity.info("deleting: " + cache.getAbsolutePath());
                boolean deleted = cache.delete();
                if (!deleted) {
                    Logging.warn("failed to delete cache file: "+cache.getAbsolutePath());
                }
            }
        }
    }

    private void eraseDonate() {
        final View view = getView();
        if (view != null) {
            final CheckBox donate = (CheckBox) view.findViewById(R.id.donate);
            donate.setEnabled(false);
            donate.setVisibility(View.GONE);
        }
    }

    /* Creates the menu items */
    @Override
    public void onCreateOptionsMenu(final Menu menu, final MenuInflater inflater) {
        MenuItem item = menu.add(0, MENU_DEBUG, 0, getString(R.string.menu_debug));
        item.setIcon( android.R.drawable.ic_media_previous );

        item = menu.add( 0, MENU_ERROR_REPORT, 0, getString(R.string.menu_error_report) );
        item.setIcon( android.R.drawable.ic_menu_report_image );

        super.onCreateOptionsMenu(menu, inflater);
    }

    /* Handles item selections */
    @Override
    public boolean onOptionsItemSelected( final MenuItem item ) {
        switch ( item.getItemId() ) {
            case MENU_DEBUG:
                final Intent debugIntent = new Intent( getActivity(), DebugActivity.class );
                this.startActivity( debugIntent );
                return true;
            case MENU_ERROR_REPORT:
                final Intent errorReportIntent = new Intent( getActivity(), ErrorReportActivity.class );
                this.startActivity( errorReportIntent );
                return true;
        }
        return false;
    }

    /**
     * used for authentication - this seems really heavy
     */
    private final static class UserDownloadHandler extends DownloadHandler {
        private SettingsFragment fragment;
        private UserDownloadHandler(final View view, final String packageName,
                                    final Resources resources, SettingsFragment settingsFragment) {
            super(view, null, packageName, resources);
            fragment = settingsFragment;
        }

        @SuppressLint("SetTextI18n")
        @Override
        public void handleMessage(final Message msg) {
            final Bundle bundle = msg.getData();
            if (msg.what == MSG_USER_DONE) {
                if ((null != bundle) && (bundle.containsKey("error"))) {
                    //ALIBI: not doing anything more here, since the toast will alert.
                    Logging.info("Settings auth unsuccessful");
                } else {
                    Logging.info("Settings auth successful");
                    final SharedPreferences prefs = MainActivity.getMainActivity()
                            .getApplicationContext()
                            .getSharedPreferences(ListFragment.SHARED_PREFS, 0);
                    final Editor editor = prefs.edit();
                    editor.remove(ListFragment.PREF_PASSWORD);
                    editor.apply();
                    //TODO: order dependent -verify no risk of race condition here.
                    fragment.updateView(view);
                }
            }
        }
    }

    private final static void addDevModeMesgIfApplicable(StringBuilder builder, final Context c, final String message) {
        if (!MainActivity.isDevMode(c)) {
            builder.append("\n\n");
            builder.append(message);
        }
    }
}

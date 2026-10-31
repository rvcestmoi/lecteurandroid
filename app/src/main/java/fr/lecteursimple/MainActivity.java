package fr.lecteursimple;

import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.media.*;
import android.media.browse.MediaBrowser;
import android.media.session.*;
import android.media.session.MediaController;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    private MediaBrowser browser;
    private MediaController controller;
    private TextView title, status, time, folder;
    private Button play, back, forward, previousTrack, nextTrack, shuffle;
    private SeekBar progress;
    private ArrayAdapter<String> songs;
    private final List<MediaBrowser.MediaItem> songItems = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean dragging, reloadPending;
    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 500); }
    };
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { refresh(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { refresh(); }
    };
    private final MediaBrowser.SubscriptionCallback subscription = new MediaBrowser.SubscriptionCallback() {
        @Override public void onChildrenLoaded(String parentId, List<MediaBrowser.MediaItem> children) {
            songItems.clear(); songItems.addAll(children);
            songs.setNotifyOnChange(false);
            songs.clear();
            if (children.isEmpty()) songs.add("Aucun titre. Choisissez un dossier contenant des fichiers audio.");
            for (MediaBrowser.MediaItem item : children) {
                songs.add(item.getDescription().getTitle().toString());
            }
            songs.notifyDataSetChanged();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        getWindow().setStatusBarColor(Color.rgb(16, 28, 35));
        getWindow().setNavigationBarColor(Color.rgb(16, 28, 35));
        ListView scroll = new ListView(this);
        scroll.setBackgroundColor(Color.rgb(16, 28, 35));
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(24)); root.setBackgroundColor(Color.rgb(16, 28, 35));
        scroll.addHeaderView(root, null, false); setContentView(scroll);
        // Respect system bars on edge-to-edge Android versions.
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text("LECTEUR SIMPLE", 15), new LinearLayout.LayoutParams(0, -2, 1));
        Button choose = button("Dossier", 14);
        choose.setContentDescription("Choisir le dossier de musique");
        choose.setMinHeight(dp(48));
        choose.setMinimumWidth(0);
        header.addView(choose, new LinearLayout.LayoutParams(-2, dp(48)));
        root.addView(header);
        title = text("Votre musique, simplement", 28);
        title.setPadding(0, dp(20), 0, dp(12));
        title.setLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setOnClickListener(v -> new android.app.AlertDialog.Builder(this).setTitle("Titre complet").setMessage(title.getText()).setPositiveButton("Fermer", null).show());
        root.addView(title);
        folder = text("", 14); root.addView(folder); updateFolder();
        choose.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(intent, 1);
        });
        status = text("Connexion au lecteur…", 16); root.addView(status);
        time = text("0:00 / 0:00", 18); time.setGravity(Gravity.CENTER); root.addView(time);
        progress = new SeekBar(this); progress.setContentDescription("Position de lecture"); root.addView(progress);
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            public void onStopTrackingTouch(SeekBar s) { if (controller != null) controller.getTransportControls().seekTo(s.getProgress()); dragging = false; }
            public void onProgressChanged(SeekBar s, int value, boolean user) { }
        });
        LinearLayout controls = new LinearLayout(this);
        back = button("↶\n−15 s", 26); play = button("▶\nLecture", 25); forward = button("↷\n+30 s", 26);
        for (Button b : new Button[]{back, play, forward}) controls.addView(b, new LinearLayout.LayoutParams(0, dp(120), 1));
        root.addView(controls);
        back.setOnClickListener(v -> { if (controller != null) controller.getTransportControls().sendCustomAction("seek_back_15", null); });
        forward.setOnClickListener(v -> { if (controller != null) controller.getTransportControls().sendCustomAction("seek_forward_30", null); });
        play.setOnClickListener(v -> {
            if (controller == null) return;
            PlaybackState s = controller.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
        });
        LinearLayout trackControls = new LinearLayout(this);
        previousTrack = button("Chanson\nprécédente", 18);
        nextTrack = button("Chanson\nsuivante", 18);
        for (Button b : new Button[]{previousTrack, nextTrack}) trackControls.addView(b, new LinearLayout.LayoutParams(0, dp(80), 1));
        root.addView(trackControls);
        previousTrack.setOnClickListener(v -> { if (controller != null) controller.getTransportControls().sendCustomAction("previous_track", null); });
        nextTrack.setOnClickListener(v -> { if (controller != null) controller.getTransportControls().sendCustomAction("next_track", null); });
        shuffle = button("Aléatoire : désactivé", 16);
        shuffle.setOnClickListener(v -> { if (controller != null) controller.getTransportControls().sendCustomAction("toggle_shuffle", null); });
        root.addView(shuffle);
        root.addView(text("Au volant : suivant = +30 s · précédent = −15 s", 14));
        TextView label = text("Titres du dossier", 22); label.setPadding(0, dp(24), 0, dp(8)); root.addView(label);
        songs = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, convertView, parent);
                row.setTextColor(Color.rgb(224, 239, 241));
                row.setTextSize(17);
                row.setMinHeight(dp(56));
                row.setPadding(dp(24), dp(12), dp(24), dp(12));
                row.setMaxLines(2);
                row.setEllipsize(android.text.TextUtils.TruncateAt.END);
                return row;
            }
        };
        scroll.setAdapter(songs);
        scroll.setOnItemClickListener((parent, view, position, id) -> {
            int selected = position - scroll.getHeaderViewsCount();
            if (controller != null && selected >= 0 && selected < songItems.size())
                controller.getTransportControls().playFromMediaId(songItems.get(selected).getMediaId(), null);
        });
        browser = new MediaBrowser(this, new ComponentName(this, PlaybackService.class), new MediaBrowser.ConnectionCallback() {
            @Override public void onConnected() {
                controller = new MediaController(MainActivity.this, browser.getSessionToken());
                setMediaController(controller); controller.registerCallback(callback);
                if (reloadPending) { controller.getTransportControls().sendCustomAction("reload", null); reloadPending = false; }
                browser.subscribe("root", subscription); refresh();
            }
            @Override public void onConnectionFailed() { status.setText("Connexion impossible. Rouvrez le lecteur."); }
            @Override public void onConnectionSuspended() { controller = null; refresh(); }
        }, null);
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(Color.rgb(224, 239, 241)); view.setPadding(0, dp(6), 0, dp(6)); return view;
    }
    private Button button(String label, int size) {
        Button b = new Button(this); b.setText(label); b.setTextSize(size); b.setAllCaps(false);
        b.setTextColor(Color.rgb(16, 28, 35)); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(100, 216, 203))); b.setMinHeight(dp(56)); return b;
    }
    private void updateFolder() {
        String name = getSharedPreferences("player", MODE_PRIVATE).getString("folderName", "Aucun dossier sélectionné");
        folder.setText(name);
    }
    private void refresh() {
        PlaybackState state = controller == null ? null : controller.getPlaybackState();
        MediaMetadata metadata = controller == null ? null : controller.getMetadata();
        if (metadata != null) title.setText(metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        boolean canSeek = state != null && (state.getActions() & PlaybackState.ACTION_SEEK_TO) != 0;
        play.setEnabled(canSeek); back.setEnabled(canSeek); forward.setEnabled(canSeek); progress.setEnabled(canSeek);
        Bundle extras = state == null ? null : state.getExtras();
        boolean shuffled = extras != null && extras.getBoolean("shuffle");
        shuffle.setEnabled(controller != null);
        shuffle.setText(shuffled ? "Aléatoire : activé" : "Aléatoire : désactivé");
        shuffle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(shuffled ? Color.rgb(100, 216, 203) : Color.rgb(173, 187, 192)));
        boolean canChangeTrack = state != null && state.getState() != PlaybackState.STATE_BUFFERING;
        previousTrack.setEnabled(canChangeTrack && extras != null && extras.getBoolean("has_previous_track"));
        nextTrack.setEnabled(canChangeTrack && extras != null && extras.getBoolean("has_next_track"));
        play.setText(playing ? "Ⅱ\nPause" : "▶\nLecture");
        if (state != null) {
            status.setText(state.getErrorMessage() != null ? state.getErrorMessage() : state.getState() == PlaybackState.STATE_BUFFERING ? "Chargement…" : playing ? "Lecture en cours" : "En pause");
            long duration = metadata == null ? 0 : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            long position = state.getPosition();
            if (playing) position += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
            position = Math.max(0, Math.min(position, duration));
            if (!dragging) { progress.setMax((int) Math.min(Integer.MAX_VALUE, duration)); progress.setProgress((int) position); }
            time.setText(format(position) + " / " + format(duration));
        }
    }
    private String format(long ms) {
        long seconds = ms / 1000;
        return seconds >= 3600 ? String.format(Locale.FRANCE, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) : String.format(Locale.FRANCE, "%d:%02d", seconds / 60, seconds % 60);
    }
    @Override protected void onStart() { super.onStart(); browser.connect(); handler.post(tick); }
    @Override protected void onStop() {
        handler.removeCallbacks(tick);
        if (controller != null) { controller.unregisterCallback(callback); controller = null; }
        browser.disconnect(); super.onStop();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 1 || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getSharedPreferences("player", MODE_PRIVATE).edit().putString("folder", uri.toString())
                .putString("folderName", "Dossier : " + android.provider.DocumentsContract.getTreeDocumentId(uri)).apply();
            updateFolder();
            if (controller != null) controller.getTransportControls().sendCustomAction("reload", null);
            else reloadPending = true;
        } catch (SecurityException e) { Toast.makeText(this, "Impossible de conserver l’accès à ce dossier. Choisissez un autre dossier.", Toast.LENGTH_LONG).show(); }
    }
}

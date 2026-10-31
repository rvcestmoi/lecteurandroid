package fr.lecteursimple;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.media.*;
import android.media.browse.MediaBrowser;
import android.media.session.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.service.media.MediaBrowserService;
import java.util.*;
import java.util.concurrent.*;

public class PlaybackService extends MediaBrowserService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<MediaBrowser.MediaItem> tracks = new ArrayList<>();
    private final PlaybackOrder order = new PlaybackOrder();
    private boolean shuffle;
    private MediaSession session;
    private MediaPlayer player;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private boolean ready, loading, resumeAfterFocus, playWhenReady;
    private int index, generation;
    private String error;
    private String pendingSearch;
    private SharedPreferences prefs;
    private SharedPreferences titleCache;
    private volatile int scanGeneration;
    private Future<?> scanTask;
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { pause(); }
    };
    private final Runnable checkpoint = new Runnable() {
        private int ticks;
        @Override public void run() {
            if (++ticks % 3 == 0) save();
            if (isPlaying()) publish(false);
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("player", MODE_PRIVATE);
        titleCache = getSharedPreferences("audio_titles_v1", MODE_PRIVATE);
        shuffle = prefs.getBoolean("shuffle", false);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(change -> {
                if (change == AudioManager.AUDIOFOCUS_GAIN) {
                    if (player != null) player.setVolume(1, 1);
                    if (resumeAfterFocus) { resumeAfterFocus = false; play(); }
                } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    if (player != null) player.setVolume(.2f, .2f);
                } else {
                    boolean resume = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && isPlaying();
                    if (isPlaying()) player.pause();
                    resumeAfterFocus = resume; save(); publish();
                    if (change == AudioManager.AUDIOFOCUS_LOSS) audio.abandonAudioFocusRequest(focus);
                }
            }, handler).build();
        session = new MediaSession(this, "LecteurSimple");
        session.setSessionActivity(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { pause(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
            @Override public void onSkipToNext() { changeTrack(1); }
            @Override public void onSkipToPrevious() { changeTrack(-1); }
            @Override public boolean onMediaButtonEvent(Intent intent) {
                android.view.KeyEvent key = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (key != null && (key.getKeyCode() == android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                        || key.getKeyCode() == android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)) {
                    // Physical media keys retain time jumps, independently of screen transport buttons.
                    if (key.getAction() == android.view.KeyEvent.ACTION_DOWN && key.getRepeatCount() == 0)
                        jump(key.getKeyCode() == android.view.KeyEvent.KEYCODE_MEDIA_NEXT ? 30000 : -15000);
                    return true;
                }
                return super.onMediaButtonEvent(intent);
            }
            @Override public void onFastForward() { jump(30000); }
            @Override public void onRewind() { jump(-15000); }
            @Override public void onSeekTo(long pos) { seek(pos); }
            @Override public void onPlayFromMediaId(String id, Bundle extras) {
                for (int i = 0; i < tracks.size(); i++) if (tracks.get(i).getMediaId().equals(id)) { open(i, 0, true); return; }
            }
            @Override public void onPlayFromSearch(String query, Bundle extras) { search(query); }
            @Override public void onCustomAction(String action, Bundle extras) {
                if ("reload".equals(action)) load();
                else if ("previous_track".equals(action)) changeTrack(-1);
                else if ("next_track".equals(action)) changeTrack(1);
                else if ("seek_back_15".equals(action)) jump(-15000);
                else if ("seek_forward_30".equals(action)) jump(30000);
                else if ("toggle_shuffle".equals(action)) {
                    shuffle = !shuffle;
                    prefs.edit().putBoolean("shuffle", shuffle).apply();
                    order.reset(tracks.size(), index, shuffle);
                    publish();
                }
            }
        });
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setActive(true);
        setSessionToken(session.getSessionToken());
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("playback", "Lecture musicale", NotificationManager.IMPORTANCE_LOW));
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        handler.post(checkpoint);
        load();
    }

    private void load() {
        save(); releasePlayer(); audio.abandonAudioFocusRequest(focus); tracks.clear(); loading = true; playWhenReady = false; error = null;
        stopForeground(STOP_FOREGROUND_REMOVE); publish();
        int request = ++generation;
        scanGeneration = request;
        if (scanTask != null) scanTask.cancel(true);
        String tree = prefs.getString("folder", "");
        scanTask = io.submit(() -> {
            List<MediaBrowser.MediaItem> found = new ArrayList<>(); String failure = null;
            List<PendingTitle> pending = new ArrayList<>();
            try {
                if (!tree.isEmpty()) {
                    Uri uri = Uri.parse(tree);
                    scan(uri, DocumentsContract.getTreeDocumentId(uri), found, pending, new HashSet<>(), request);
                    found.sort(Comparator.comparing(item -> item.getDescription().getTitle().toString(), String.CASE_INSENSITIVE_ORDER));
                }
            } catch (Exception e) { failure = "Dossier inaccessible. Choisissez à nouveau le dossier."; }
            String message = failure;
            handler.post(() -> {
                if (request != generation) return;
                loading = false; tracks.addAll(found); error = message;
                notifyChildrenChanged("root");
                String last = prefs.getString("track", ""); int selected = 0;
                for (int i = 0; i < tracks.size(); i++) if (tracks.get(i).getMediaId().equals(last)) selected = i;
                order.reset(tracks.size(), selected, shuffle);
                if (!tracks.isEmpty()) open(selected, tracks.get(selected).getMediaId().equals(last) ? prefs.getLong("position", 0) : 0, false);
                else publish();
                if (pendingSearch != null) { String query = pendingSearch; pendingSearch = null; search(query); }
            });
            // Enumeration is already delivered: tag extraction never blocks the first playable list.
            Map<String, String> updates = new HashMap<>();
            SharedPreferences.Editor cachedTitles = titleCache.edit();
            long deliveredAt = SystemClock.elapsedRealtime();
            for (PendingTitle item : pending) {
                if (request != scanGeneration || Thread.currentThread().isInterrupted()) return;
                String title = readTitle(item.uri, item.filename);
                if (request != scanGeneration || Thread.currentThread().isInterrupted()) return;
                cachedTitles.putString(item.uri.toString(), item.signature + "\n" + title);
                updates.put(item.uri.toString(), title);
                if (updates.size() >= 20 || SystemClock.elapsedRealtime() - deliveredAt >= 750) {
                    cachedTitles.apply(); cachedTitles = titleCache.edit();
                    deliverTitles(request, updates); updates = new HashMap<>();
                    deliveredAt = SystemClock.elapsedRealtime();
                }
            }
            if (!updates.isEmpty()) { cachedTitles.apply(); deliverTitles(request, updates); }
        });
    }

    private static final class PendingTitle {
        final Uri uri;
        final String filename, signature;
        PendingTitle(Uri uri, String filename, String signature) { this.uri = uri; this.filename = filename; this.signature = signature; }
    }

    private void deliverTitles(int request, Map<String, String> updates) {
        handler.post(() -> {
            if (request != generation) return;
            for (int i = 0; i < tracks.size(); i++) {
                MediaBrowser.MediaItem item = tracks.get(i);
                String title = updates.get(item.getMediaId());
                if (title != null) tracks.set(i, new MediaBrowser.MediaItem(new MediaDescription.Builder()
                    .setMediaId(item.getMediaId()).setMediaUri(item.getDescription().getMediaUri()).setTitle(title).build(), MediaBrowser.MediaItem.FLAG_PLAYABLE));
            }
            // Keep indices stable: a title arriving must not change the current song or shuffle history.
            notifyChildrenChanged("root"); publish();
        });
    }

    private void scan(Uri tree, String id, List<MediaBrowser.MediaItem> result, List<PendingTitle> pending, Set<String> visited, int request) {
        if (!visited.add(id) || request != scanGeneration || Thread.currentThread().isInterrupted()) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        try (Cursor cursor = getContentResolver().query(children, new String[]{"document_id", "_display_name", "mime_type", "last_modified", "_size"}, null, null, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                if (request != scanGeneration || Thread.currentThread().isInterrupted()) return;
                String child = cursor.getString(0), name = cursor.getString(1), mime = cursor.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) scan(tree, child, result, pending, visited, request);
                else if ((mime != null && mime.startsWith("audio/")) || (name != null && name.toLowerCase(Locale.ROOT).matches(".*\\.(mp3|m4a|aac|flac|ogg|opus|wav)"))) {
                    Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, child);
                    String signature = (cursor.isNull(3) ? -1 : cursor.getLong(3)) + ":" + (cursor.isNull(4) ? -1 : cursor.getLong(4)) + ":" + name;
                    String cached = titleCache.getString(uri.toString(), "");
                    String prefix = signature + "\n";
                    String title = cached.startsWith(prefix) ? cached.substring(prefix.length()) : name;
                    if (title == null || title.trim().isEmpty()) title = "Titre inconnu";
                    if (!cached.startsWith(prefix)) pending.add(new PendingTitle(uri, name, signature));
                    result.add(new MediaBrowser.MediaItem(new android.media.MediaDescription.Builder().setMediaId(uri.toString()).setMediaUri(uri).setTitle(title).build(), MediaBrowser.MediaItem.FLAG_PLAYABLE));
                }
            }
        }
    }

    // Called by the library scan on the I/O executor, never on the UI thread.
    private String readTitle(Uri uri, String filename) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            String title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            if (title != null && !title.trim().isEmpty()) return title.trim();
        } catch (RuntimeException ignored) {
            // Missing, unreadable or unsupported tags must not hide a playable file.
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
        return filename == null || filename.trim().isEmpty() ? "Titre inconnu" : filename;
    }

    private boolean isPlaying() { return ready && player != null && player.isPlaying(); }
    private void changeTrack(int direction) {
        int selected = order.adjacent(index, direction);
        if (loading || selected < 0 || selected >= tracks.size()) return;
        boolean autoplay = isPlaying() || playWhenReady;
        open(selected, 0, autoplay);
    }
    private void search(String query) {
        if (loading) { pendingSearch = query == null ? "" : query; return; }
        if (query == null || query.trim().isEmpty()) { play(); return; }
        String needle = query.toLowerCase(Locale.FRANCE).trim();
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).getDescription().getTitle().toString().toLowerCase(Locale.FRANCE).contains(needle)) { open(i, 0, true); return; }
        }
        pause(); error = "Aucun titre ne correspond à la recherche."; publish();
    }
    private void open(int selected, long position, boolean autoplay) {
        save(); releasePlayer(); index = selected; error = null;
        MediaPlayer next = new MediaPlayer(); player = next;
        next.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        next.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        next.setOnPreparedListener(p -> {
            if (player != p) return;
            ready = true; p.seekTo(Math.min(position, p.getDuration()), MediaPlayer.SEEK_CLOSEST);
            if (autoplay || playWhenReady) { playWhenReady = false; play(); } else publish();
        });
        next.setOnSeekCompleteListener(p -> { if (player == p && ready) { save(); publish(); } });
        next.setOnCompletionListener(p -> {
            int following = order.adjacent(index, 1);
            if (following >= 0) open(following, 0, true);
            else { seek(0); pause(); }
        });
        next.setOnErrorListener((p, what, extra) -> { error = "Impossible de lire ce fichier. Sélectionnez un autre titre."; releasePlayer(); publish(); stopForeground(STOP_FOREGROUND_REMOVE); return true; });
        try { next.setDataSource(this, Uri.parse(tracks.get(index).getMediaId())); next.prepareAsync(); publish(); }
        catch (Exception e) { error = "Fichier inaccessible ou format non pris en charge."; releasePlayer(); publish(); }
    }

    private void play() {
        if (!ready) { playWhenReady = true; return; }
        error = null;
        // Android 15 requires a visible app or a foreground service before requesting focus.
        try {
            startForeground(1, notification());
            if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { stopForeground(STOP_FOREGROUND_DETACH); return; }
            startService(new Intent(this, PlaybackService.class));
            player.start(); publish();
        } catch (RuntimeException e) { error = "Ouvrez le lecteur sur le téléphone pour démarrer la lecture."; publish(); }
    }
    private void pause() {
        resumeAfterFocus = false; playWhenReady = false;
        if (isPlaying()) player.pause();
        save(); audio.abandonAudioFocusRequest(focus); publish(); stopForeground(STOP_FOREGROUND_DETACH);
    }
    private void seek(long pos) {
        if (ready) { player.seekTo(Math.max(0, Math.min(pos, player.getDuration())), MediaPlayer.SEEK_CLOSEST); publish(); }
    }
    private void jump(long delta) { if (ready) seek(player.getCurrentPosition() + delta); }
    private void save() {
        if (ready && !tracks.isEmpty()) prefs.edit().putString("track", tracks.get(index).getMediaId()).putLong("position", player.getCurrentPosition()).apply();
    }
    private void releasePlayer() { ready = false; if (player != null) { player.release(); player = null; } }

    @android.annotation.SuppressLint("NotificationPermission") // Media-session notifications are exempt from POST_NOTIFICATIONS.
    private void publish() { publish(true); }

    @android.annotation.SuppressLint("NotificationPermission") // MediaStyle notification with a session token.
    private void publish(boolean updateNotification) {
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID | PlaybackState.ACTION_PLAY_FROM_SEARCH;
        // Screen transport buttons change tracks; physical media keys are handled separately above.
        if (ready) actions |= PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO;
        int state = error != null ? PlaybackState.STATE_ERROR : loading || (player != null && !ready) ? PlaybackState.STATE_BUFFERING : isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        Bundle extras = new Bundle();
        extras.putBoolean("shuffle", shuffle);
        extras.putBoolean("has_previous_track", !tracks.isEmpty() && order.adjacent(index, -1) >= 0);
        extras.putBoolean("has_next_track", !tracks.isEmpty() && order.adjacent(index, 1) >= 0);
        PlaybackState.Builder status = new PlaybackState.Builder().setActions(actions).setExtras(extras).setState(state, ready ? player.getCurrentPosition() : 0, isPlaying() ? 1 : 0);
        if (ready) {
            status.addCustomAction(new PlaybackState.CustomAction.Builder("seek_back_15", "Reculer de 15 secondes", R.drawable.ic_back_15).build());
            status.addCustomAction(new PlaybackState.CustomAction.Builder("seek_forward_30", "Avancer de 30 secondes", R.drawable.ic_forward_30).build());
        }
        if (error != null) status.setErrorMessage(error);
        String title = tracks.isEmpty() ? (loading ? "Recherche des fichiers…" : "Choisissez un dossier de musique") : tracks.get(index).getDescription().getTitle().toString();
        long duration = ready ? player.getDuration() : 0;
        String timer = formatTime(ready ? player.getCurrentPosition() : 0) + " / " + formatTime(duration);
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, tracks.isEmpty() ? "" : tracks.get(index).getMediaId())
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, timer)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
            .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, tracks.isEmpty() ? 0 : index + 1)
            .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, tracks.size()).build());
        session.setPlaybackState(status.build());
        if (ready && updateNotification) getSystemService(NotificationManager.class).notify(1, notification());
    }
    private static String formatTime(long milliseconds) {
        long seconds = Math.max(0, milliseconds) / 1000;
        return seconds >= 3600
            ? String.format(Locale.FRANCE, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            : String.format(Locale.FRANCE, "%d:%02d", seconds / 60, seconds % 60);
    }
    private Notification notification() {
        boolean playing = isPlaying();
        return new Notification.Builder(this, "playback").setSmallIcon(fr.lecteursimple.R.drawable.ic_music)
            .setContentTitle(tracks.isEmpty() ? "Lecteur simple" : tracks.get(index).getDescription().getTitle())
            .setContentText("Précédent : −15 s • Suivant : +30 s")
            .setContentIntent(session.getController().getSessionActivity()).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_rew, "−15 s", action("back")).build())
            .addAction(new Notification.Action.Builder(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "Pause" : "Lecture", action(playing ? "pause" : "play")).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_ff, "+30 s", action("forward")).build())
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2)).build();
    }
    private PendingIntent action(String action) {
        return PendingIntent.getService(this, action.hashCode(), new Intent(this, PlaybackService.class).setAction(action), PendingIntent.FLAG_IMMUTABLE);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH.equals(intent.getAction())) search(intent.getStringExtra(android.app.SearchManager.QUERY));
        if (intent != null && intent.getAction() != null) switch (intent.getAction()) {
            case "play": play(); break; case "pause": pause(); break;
            case "back": jump(-15000); break; case "forward": jump(30000); break;
        }
        return START_NOT_STICKY;
    }
    @Override public BrowserRoot onGetRoot(String packageName, int uid, Bundle hints) { return new BrowserRoot("root", null); }
    @Override public void onLoadChildren(String parent, Result<List<MediaBrowser.MediaItem>> result) { result.sendResult("root".equals(parent) ? new ArrayList<>(tracks) : Collections.emptyList()); }
    @Override public void onDestroy() {
        scanGeneration = ++generation; handler.removeCallbacksAndMessages(null); io.shutdownNow(); save(); releasePlayer();
        audio.abandonAudioFocusRequest(focus); unregisterReceiver(noisy); session.release();
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}

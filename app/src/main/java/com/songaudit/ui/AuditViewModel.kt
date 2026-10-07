package com.songaudit.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.songaudit.analysis.Issue
import com.songaudit.library.Album
import com.songaudit.library.Copy
import com.songaudit.library.Db
import com.songaudit.library.Doctor
import com.songaudit.library.DupGroup
import com.songaudit.library.Duplicates
import com.songaudit.library.Finding
import com.songaudit.library.Quarantine
import com.songaudit.library.Settings
import com.songaudit.library.Storage
import com.songaudit.library.Track
import com.songaudit.library.Volume
import com.songaudit.scan.Progress
import com.songaudit.scan.ScanService
import com.songaudit.scan.ScanState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the screens show, computed once per database change. */
class Library(
    val tracks: List<Track>,
    val albums: List<Album>,
    val byFolder: Map<String, Album>,
    val findings: List<Finding>,
    val groups: List<DupGroup>,
    val quarantine: List<Db.Moved>,
) {
    val size: Long = tracks.sumOf { it.size }
    val analysed: Int = tracks.count { it.analysed }

    val damaged: List<Album> = albums.filter { it.count(Issue.DAMAGED) > 0 }

    /** Albums with at least one track that is not what its format claims. */
    val fakes: List<Album> = albums.filter { a -> FAKES.any { a.count(it) > 0 } }

    val duplicates: List<DupGroup> = groups.filter { !it.otherRelease }
    val otherReleases: List<DupGroup> = groups.filter { it.otherRelease }
    val reclaimable: Long = duplicates.sumOf { it.reclaimable }
    val quarantineSize: Long = quarantine.sumOf { it.size }

    companion object {
        val FAKES = listOf(Issue.LOSSY_SOURCE, Issue.MAYBE_LOSSY, Issue.UPSAMPLED, Issue.PADDED, Issue.REENCODED)
        val EMPTY = Library(emptyList(), emptyList(), emptyMap(), emptyList(), emptyList(), emptyList())
    }
}

class Options(val volumes: List<Volume>, val skipped: Set<String>, val onlyWhileCharging: Boolean, val lastScan: Long)

class AuditViewModel(app: Application) : AndroidViewModel(app) {

    private val db = Db.get(app)
    private val settings = Settings(app)
    private val quarantine = Quarantine(app)

    val progress: StateFlow<Progress> = ScanState.progress

    private val _library = MutableStateFlow<Library?>(null)
    val library: StateFlow<Library?> = _library

    private val _access = MutableStateFlow(Storage.hasAccess(app))
    val access: StateFlow<Boolean> = _access

    private val _options = MutableStateFlow(options())
    val options: StateFlow<Options> = _options

    /** The person's own choice of which copy to keep, by group, overriding the suggestion. */
    private val _keep = MutableStateFlow<Map<String, String>>(emptyMap())
    val keep: StateFlow<Map<String, String>> = _keep

    /** A line to show once at the bottom of the screen: what just happened. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    init {
        viewModelScope.launch {
            progress.map { it.version }.distinctUntilChanged().collectLatest { reload() }
        }
    }

    private suspend fun reload() {
        _options.value = options()
        if (!Storage.hasAccess(getApplication())) return
        val roots = options.value.let { o -> o.volumes.map { it.path }.filter { it !in o.skipped } }
        val lib = withContext(Dispatchers.Default) {
            // A card that is out, or left out of the scan, keeps its results for later but is not shown.
            val tracks = db.tracks().filter { t -> roots.any { t.path.startsWith("$it/") } }
            val albums = Album.of(tracks)
            Library(
                tracks = tracks,
                albums = albums,
                byFolder = albums.associateBy { it.folder },
                findings = Doctor.examine(albums, db.coverFolders()),
                groups = Duplicates.group(tracks, db.edges()),
                quarantine = db.moved(),
            )
        }
        _library.value = lib
    }

    fun refreshAccess() {
        val had = _access.value
        _access.value = Storage.hasAccess(getApplication())
        _options.value = options()
        if (!had && _access.value) viewModelScope.launch { reload() }
    }

    private fun options() = Options(Storage.volumes(getApplication()), settings.skippedRoots, settings.onlyWhileCharging, settings.lastScan)

    fun scan() = ScanService.start(getApplication())
    fun stop() = ScanService.stop(getApplication())

    fun toggleVolume(path: String) {
        val now = settings.skippedRoots
        settings.skippedRoots = if (path in now) now - path else now + path
        _options.value = options()
        viewModelScope.launch { reload() }
    }

    fun toggleCharging() {
        settings.onlyWhileCharging = !settings.onlyWhileCharging
        _options.value = options()
    }

    fun spectrum(id: Long): ByteArray? = db.spectrum(id)

    // -- Duplicates -------------------------------------------------------

    fun choose(group: DupGroup, copy: Copy) = _keep.update { it + (group.id to copy.path) }

    /** The copy to keep in [group]: the person's pick if they made one, else the suggestion. */
    fun kept(group: DupGroup, picks: Map<String, String>): Copy =
        picks[group.id]?.let { p -> group.copies.firstOrNull { it.path == p } } ?: group.keep

    fun quarantine(groups: List<DupGroup>) {
        val picks = _keep.value
        val lib = _library.value ?: return
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val keepers = groups.associateWith { kept(it, picks) }
                quarantine.move(groups.flatMap { g -> g.copies.filter { it !== keepers[g] } }, lib.tracks)
            }
            _notice.value = buildString {
                append("Moved ${outcome.moved} to quarantine · ${Doctor.mb(outcome.bytes)}")
                if (outcome.failed.isNotEmpty()) append(" · ${outcome.failed.size} could not be moved")
            }
            ScanState.changed()
        }
    }

    fun restore(entries: List<Db.Moved>) {
        viewModelScope.launch {
            val failed = withContext(Dispatchers.IO) { quarantine.restore(entries) }
            _notice.value = if (failed.isEmpty()) "Put back ${entries.size}" else "${failed.size} could not be put back: the place is taken"
            ScanState.changed()
        }
    }

    fun emptyQuarantine() {
        val entries = _library.value?.quarantine ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { quarantine.empty(entries) }
            _notice.value = "Quarantine emptied · ${Doctor.mb(entries.sumOf { it.size })} freed"
            ScanState.changed()
        }
    }

    fun noticeShown() {
        _notice.value = null
    }
}

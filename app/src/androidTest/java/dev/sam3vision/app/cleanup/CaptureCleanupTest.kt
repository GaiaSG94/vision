package dev.sam3vision.app.cleanup

import android.content.ContentUris
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Preuves instrumentées du module de nettoyage :
 * 1. les captures de l'app sont supprimées ;
 * 2. un fichier déposé hors du périmètre n'est pas touché ;
 * 3. une trace MediaStore dé-synchronisée (entrée déjà disparue) est gérée silencieusement ;
 * 4. une capture en cours d'écriture (mtime < 60 s) est reportée.
 */
@RunWith(AndroidJUnit4::class)
class CaptureCleanupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var videos: File
    private lateinit var images: File
    private lateinit var horsPerimetre: File
    private lateinit var db: CleanupDatabase

    @Before
    fun setUp() {
        videos = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)).apply { mkdirs() }
        images = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)).apply { mkdirs() }
        horsPerimetre = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)).apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(context, CleanupDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
        videos.deleteRecursively()
        images.deleteRecursively()
        horsPerimetre.deleteRecursively()
    }

    /** Fichier daté d'il y a [ageMinutes] minutes (au-delà du délai d'écriture en cours). */
    private fun capture(dir: File, name: String, ageMinutes: Long = 10L): File =
        File(dir, name).apply {
            writeText("capture")
            setLastModified(System.currentTimeMillis() - ageMinutes * 60_000L)
        }

    private fun cleaner() = CaptureCleaner(
        roots = CapturePaths.privateRoots(context),
        store = RoomTracedCaptureStore(db.tracedCaptures(), context.contentResolver, context.packageName),
    )

    @Test
    fun uneCaptureDeLAppEstSupprimee() = runBlocking {
        val video = capture(videos, "tap-1.mp4")
        val photo = capture(images, "photo-1.jpg")
        val report = cleaner().runOnce()
        assertFalse(video.exists())
        assertFalse(photo.exists())
        assertTrue(report.filesDeleted >= 2)
    }

    @Test
    fun unFichierHorsPerimetreNEstPasTouche() = runBlocking {
        val dehors = capture(horsPerimetre, "a-un-autre-endroit.jpg")
        val dedans = capture(videos, "tap-2.mp4")
        val report = cleaner().runOnce()
        assertTrue(dehors.exists())
        assertFalse(dedans.exists())
        assertTrue(report.filesDeleted >= 1)
    }

    @Test
    fun uneTraceMediaStoreDesynchroniseeEstOublieeSansErreur() = runBlocking {
        val trace = TracedCapture(
            mediaStoreId = Long.MAX_VALUE,
            uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Long.MAX_VALUE).toString(),
            relativePath = "Pictures/SamVision",
            kind = TracedCapture.KIND_IMAGE,
            createdAtMs = System.currentTimeMillis(),
        )
        db.tracedCaptures().insert(trace)
        val report = cleaner().runOnce() // aucune exception attendue
        assertEquals(0, report.tracedLeft)
        assertEquals(0, db.tracedCaptures().count())
    }

    @Test
    fun uneCaptureEnCoursDEcritureEstReportee() = runBlocking {
        val enCours = capture(videos, "tap-3.mp4", ageMinutes = 0L)
        val report = cleaner().runOnce()
        assertTrue(enCours.exists())
        assertEquals(1, report.skippedInProgress)
        assertEquals(0, report.filesDeleted)
    }

    @Test
    fun lePerimetreNeContientQueLeStockagePriveDeLApp() {
        val roots = CapturePaths.privateRoots(context)
        assertTrue(roots.all { it.absolutePath.contains(context.packageName) })
        assertTrue(roots.none { it.absolutePath.startsWith("/storage/emulated/0/Pictures") })
    }

    @Test
    fun leJobHoraireEstUniqueEtSansReseau() {
        CleanupScheduler.schedule(context)
        CleanupScheduler.schedule(context) // KEEP : jamais de doublon
        val infos = androidx.work.WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(CleanupConfig.WORK_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(
            androidx.work.NetworkType.NOT_REQUIRED,
            infos.single().constraints.requiredNetworkType,
        )
    }
}

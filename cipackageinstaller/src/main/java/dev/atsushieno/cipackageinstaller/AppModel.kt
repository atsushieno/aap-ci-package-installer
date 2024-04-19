package dev.atsushieno.cipackageinstaller

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.icu.util.ULocale
import android.os.Build
import android.os.FileUtils
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

val AppModel by lazy { AppModelFactory.create() }

abstract class ApplicationModel {
    companion object {
        private const val PENDING_INTENT_REQUEST_CODE = 1
        private const val PENDING_PREAPPROVAL_REQUEST_CODE = 2
        private const val FILE_APK_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"
    }
    abstract val LOG_TAG: String
    abstract val installerSessionReferrer: String

    // it is made overridable
    open val applicationStore: RepositoryCatalogProvider
        get() = githubApplicationStore

    val preApprovalEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE || Build.VERSION.CODENAME == "UpsideDownCake"

    private fun createSharedPreferences(context: Context) : SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(context, "AndroidCIPackageInstaller", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    // FIXME: it is too tied to GitHub. We should provide somewhat more generic way to provide credentials to other stores.
    fun getGitHubCredentials(context: Context) : GitHubRepositoryCatalogProvider.GitHubCredentials {
        val sp = createSharedPreferences(context)
        val user = sp.getString("GITHUB_USER", "") ?: ""
        val pat = sp.getString("GITHUB_PAT", "") ?: ""
        return GitHubRepositoryCatalogProvider.GitHubCredentials(user, pat)
    }

    // FIXME: it is too tied to GitHub. We should provide somewhat more generic way to provide credentials to other stores.
    fun setGitHubCredentials(context: Context, username: String, pat: String) {
        val sp = createSharedPreferences(context)
        val edit = sp.edit()
        edit.putString("GITHUB_USER", username)
        edit.putString("GITHUB_PAT", pat)
        edit.apply()

        githubApplicationStore.updateCredentials(username, pat)
    }

    fun copyStream(inFS: InputStream, outFile: File) {
        val outFS = FileOutputStream(outFile)
        copyStream(inFS, outFS)
        outFS.close()
    }

    private fun copyStream(inFS: InputStream, outFS: OutputStream) {
        val bytes = ByteArray(4096)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            FileUtils.copy(inFS, outFS)
        } else {
            while (inFS.available() > 0) {
                val size = inFS.read(bytes)
                outFS.write(bytes, 0, size)
            }
        }
    }

    // Process permissions and then download and launch pending installation intent
    // (that may involve user interaction).
    fun performDownloadAndInstallation(context: Context, download: ApplicationArtifact) {
        val repo = download.repository
        val installer = context.packageManager.packageInstaller

        val existing = installer.mySessions.firstOrNull { it.appPackageName == repo.info.packageName && !it.isActive }
        if (existing != null)
            // abandon existing session and restart than throwing, when we perform install->uninstall->install...
            installer.openSession(existing.sessionId).abandon()

        val params = download.toPackageInstallerSessionParams()
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)

        // Pre-approval is available only in Android 14 or later.
        if (preApprovalEnabled) {
            val preapprovalIntent = Intent(context, PreapprovalReceiver::class.java)
            val preapprovalPendingIntent = PendingIntent.getBroadcast(context,
                PENDING_PREAPPROVAL_REQUEST_CODE, preapprovalIntent, PendingIntent.FLAG_MUTABLE)
            val preapproval = PackageInstaller.PreapprovalDetails.Builder()
                .setPackageName(repo.info.packageName)
                .setLabel(repo.info.appLabel)
                .setLocale(ULocale.getDefault())
                .build()
            session.requestUserPreapproval(preapproval, preapprovalPendingIntent.intentSender)
        }

        val file = download.downloadApp()
        val outStream = session.openWrite(file.name, 0, file.length())
        val inStream = FileInputStream(file)
        copyStream(inStream, outStream)
        session.fsync(outStream)
        outStream.close()

        val intent = Intent(context, PackageInstallerReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(context, PENDING_INTENT_REQUEST_CODE,
            intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        Log.d(LOG_TAG, "ready to install ${repo.info.appLabel} ...")
        session.commit(pendingIntent.intentSender)
        session.close()
    }

    fun performUninstallPackage(context: Context, repo: Repository) {
        val installer = context.packageManager.packageInstaller
        val intent = Intent(context, PackageInstallerReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(context, PENDING_INTENT_REQUEST_CODE,
            intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        installer.uninstall(repo.info.packageName, pendingIntent.intentSender)
    }

    // provide access to GitHub specific properties such as `guthubRepositories`
    val githubApplicationStore  by lazy { GitHubRepositoryCatalogProvider() }

    // This method is used to find the relevant packages that are already installed in an explicit way.
    // (We cannot simply query existing (installed) apps that exposes users privacy.)
    // Override it to determine which apps are in your installer's targets.
    // For example, AAP APK Installer targets AudioPluginServices (FIXME: it should also include hosts...).
    var findExistingPackages: (Context) -> List<String> = { listOf() }

    var isExistingPackageListReliable: () -> Boolean = { false }
}

object AppModelFactory {
    var create: () -> ApplicationModel = { TODO("It must be declared by each application") }
}

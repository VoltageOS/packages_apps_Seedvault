/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_NOT_INITIALIZED
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.app.backup.BackupTransport.TRANSPORT_PACKAGE_REJECTED
import android.app.backup.BackupTransport.TRANSPORT_QUOTA_EXCEEDED
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.coAssertThrows
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.metadata.BackupType
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.metadata.PackageState.NO_DATA
import com.stevesoltys.seedvault.metadata.PackageState.QUOTA_EXCEEDED
import com.stevesoltys.seedvault.metadata.PackageState.UNKNOWN_ERROR
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import com.stevesoltys.seedvault.worker.ApkBackup
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.LegacyAppBackupFile
import org.calyxos.seedvault.core.backends.saf.SafProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.OutputStream
import kotlin.random.Random

internal class BackupCoordinatorTest : BackupTest() {

    private val backendManager = mockk<BackendManager>()
    private val appBackupManager = mockk<AppBackupManager>()
    private val kv = mockk<KVBackup>()
    private val full = mockk<FullBackup>()
    private val apkBackup = mockk<ApkBackup>()
    private val notificationManager = mockk<BackupNotificationManager>()
    private val packageService = mockk<PackageService>()

    private val backup = BackupCoordinator(
        context = context,
        backendManager = backendManager,
        appBackupManager = appBackupManager,
        kv = kv,
        full = full,
        clock = clock,
        packageService = packageService,
        metadataManager = metadataManager,
        settingsManager = settingsManager,
        nm = notificationManager,
    )

    private val backend = mockk<Backend>()
    private val metadataOutputStream = mockk<OutputStream>()
    private val fileDescriptor: ParcelFileDescriptor = mockk()
    private val packageMetadata: PackageMetadata = mockk()
    private val safProperties = SafProperties(
        config = Uri.EMPTY,
        name = getRandomString(),
        isUsb = false,
        requiresNetwork = false,
        rootId = null,
    )

    init {
        every { backendManager.backend } returns backend
    }

    @Test
    fun `device initialization succeeds and delegates to plugin`() = runBlocking {
        expectStartNewRestoreSet()
        every { kv.hasState } returns false
        every { full.hasState } returns false

        assertEquals(TRANSPORT_OK, backup.initializeDevice())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
    }

    private fun expectStartNewRestoreSet() {
        every { clock.time() } returns token
        every { settingsManager.setNewToken(token) } just Runs
        every { metadataManager.onDeviceInitialization(token) } just Runs
    }

    @Test
    fun `error notification when device initialization fails`() = runBlocking {
        val maybeTrue = Random.nextBoolean()

        every { clock.time() } returns token
        every { settingsManager.setNewToken(token) } just Runs
        every { metadataManager.onDeviceInitialization(token) } throws IOException()
        every { metadataManager.requiresInit } returns maybeTrue
        every { backendManager.canDoBackupNow() } returns !maybeTrue
        every { notificationManager.onBackupError() } just Runs

        assertEquals(TRANSPORT_ERROR, backup.initializeDevice())

        // finish will only be called when TRANSPORT_OK is returned, so it should throw
        every { kv.hasState } returns false
        every { full.hasState } returns false
        coAssertThrows(IllegalStateException::class.java) {
            backup.finishBackup()
        }
    }

    @Test
    fun `no error notification when device initialization fails when no backup possible`() =
        runBlocking {
            every { clock.time() } returns token
            every { settingsManager.setNewToken(token) } just Runs
            every { metadataManager.onDeviceInitialization(token) } throws IOException()
            every { metadataManager.requiresInit } returns false
            every { backendManager.canDoBackupNow() } returns false

            assertEquals(TRANSPORT_ERROR, backup.initializeDevice())

            // finish will only be called when TRANSPORT_OK is returned, so it should throw
            every { kv.hasState } returns false
            every { full.hasState } returns false
            coAssertThrows(IllegalStateException::class.java) {
                backup.finishBackup()
            }
        }

    @Test
    fun `performIncrementalBackup of @pm@ causes re-init when legacy format`() = runBlocking {
        val packageInfo = PackageInfo().apply { packageName = MAGIC_PACKAGE_MANAGER }

        every { backendManager.canDoBackupNow() } returns true
        every { metadataManager.requiresInit } returns true

        // start new restore set
        every { clock.time() } returns token + 1
        every { settingsManager.setNewToken(token + 1) } just Runs
        every { metadataManager.onDeviceInitialization(token + 1) } just Runs

        every { data.close() } just Runs

        // returns TRANSPORT_NOT_INITIALIZED to re-init next time
        assertEquals(
            TRANSPORT_NOT_INITIALIZED,
            backup.performIncrementalBackup(packageInfo, data, 0)
        )
    }

    @Test
    fun `getBackupQuota() delegates to right plugin`() = runBlocking {
        val isFullBackup = Random.nextBoolean()
        val quota = Random.nextLong()

        if (isFullBackup) {
            every { full.quota } returns quota
        } else {
            every { kv.quota } returns quota
        }
        assertEquals(quota, backup.getBackupQuota(packageInfo.packageName, isFullBackup))
    }

    @Test
    fun `clearing backup data does nothing`() = runBlocking {
        assertEquals(TRANSPORT_OK, backup.clearBackupData(packageInfo))

        every { kv.hasState } returns false
        every { full.hasState } returns false

        assertEquals(TRANSPORT_OK, backup.finishBackup())
    }

    @Test
    fun `finish backup delegates to KV plugin if it has state`() = runBlocking {
        val snapshotCreator: SnapshotCreator = mockk()
        val size = Random.nextLong()

        every { kv.hasState } returns true
        every { full.hasState } returns false
        every { kv.currentPackageInfo } returns packageInfo
        coEvery { kv.finishBackup() } returns apkBackupData
        every { appBackupManager.snapshotCreator } returns snapshotCreator
        every {
            snapshotCreator.onPackageBackedUp(packageInfo, BackupType.KV, apkBackupData)
        } just Runs
        every {
            metadataManager.onPackageBackedUp(packageInfo, BackupType.KV, apkBackupData.size)
        } just Runs

        assertEquals(TRANSPORT_OK, backup.finishBackup())
    }

    @Test
    fun `finish KV backup throws exception`() = runBlocking {
        every { kv.hasState } returns true
        every { full.hasState } returns false
        every { kv.currentPackageInfo } returns packageInfo
        coEvery { kv.finishBackup() } throws IOException()

        every { settingsManager.getToken() } returns token
        every {
            metadataManager.onPackageBackupError(
                packageInfo,
                UNKNOWN_ERROR,
                metadataOutputStream,
                BackupType.KV,
            )
        } just Runs
        coEvery { backend.save(LegacyAppBackupFile.Metadata(token)) } returns metadataOutputStream
        every { metadataOutputStream.close() } just Runs

        assertEquals(TRANSPORT_PACKAGE_REJECTED, backup.finishBackup())
    }

    @Test
    fun `finish backup delegates to full plugin if it has state`() = runBlocking {
        val snapshotCreator: SnapshotCreator = mockk()
        val size: Long = 2345

        every { kv.hasState } returns false
        every { full.hasState } returns true
        every { full.currentPackageInfo } returns packageInfo
        coEvery { full.finishBackup() } returns apkBackupData
        every { appBackupManager.snapshotCreator } returns snapshotCreator
        every {
            snapshotCreator.onPackageBackedUp(packageInfo, BackupType.FULL, apkBackupData)
        } just Runs
        every {
            metadataManager.onPackageBackedUp(
                packageInfo = packageInfo,
                type = BackupType.FULL,
                size = apkBackupData.size,
            )
        } just Runs

        assertEquals(TRANSPORT_OK, backup.finishBackup())
    }

    @Test
    fun `metadata does not get updated when no APK was backed up`() = runBlocking {
        coEvery {
            full.performFullBackup(packageInfo, fileDescriptor, 0)
        } returns TRANSPORT_OK
        coEvery { apkBackup.backupApkIfNecessary(packageInfo) } just Runs

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, fileDescriptor, 0))
    }

    @Test
    fun `app exceeding quota gets cancelled and reason written to metadata`() = runBlocking {
        coEvery {
            full.performFullBackup(packageInfo, fileDescriptor, 0)
        } returns TRANSPORT_OK
        expectApkBackupAndMetadataWrite()
        every { full.quota } returns DEFAULT_QUOTA_FULL_BACKUP
        every {
            full.checkFullBackupSize(DEFAULT_QUOTA_FULL_BACKUP + 1)
        } returns TRANSPORT_QUOTA_EXCEEDED
        every { full.currentPackageInfo } returns packageInfo
        every {
            metadataManager.onPackageBackupError(
                packageInfo,
                QUOTA_EXCEEDED,
                metadataOutputStream,
                BackupType.FULL
            )
        } just Runs
        coEvery { full.cancelFullBackup() } just Runs
        every { backendManager.backendProperties } returns safProperties
        every { settingsManager.useMeteredNetwork } returns false
        every { metadataOutputStream.close() } just Runs

        assertEquals(
            TRANSPORT_OK,
            backup.performFullBackup(packageInfo, fileDescriptor, 0)
        )
        assertEquals(
            DEFAULT_QUOTA_FULL_BACKUP,
            backup.getBackupQuota(packageInfo.packageName, true)
        )
        assertEquals(
            TRANSPORT_QUOTA_EXCEEDED,
            backup.checkFullBackupSize(DEFAULT_QUOTA_FULL_BACKUP + 1)
        )
        backup.cancelFullBackup()
        assertEquals(0L, backup.requestFullBackupTime())

        verify(exactly = 1) {
            metadataManager.onPackageBackupError(
                packageInfo,
                QUOTA_EXCEEDED,
                metadataOutputStream,
                BackupType.FULL
            )
        }
        verify { metadataOutputStream.close() }
    }

    @Test
    fun `app with no data gets cancelled and reason written to metadata`() = runBlocking {
        coEvery {
            full.performFullBackup(packageInfo, fileDescriptor, 0)
        } returns TRANSPORT_OK
        expectApkBackupAndMetadataWrite()
        every { full.quota } returns DEFAULT_QUOTA_FULL_BACKUP
        every { full.checkFullBackupSize(0) } returns TRANSPORT_PACKAGE_REJECTED
        every { full.currentPackageInfo } returns packageInfo
        every {
            metadataManager.onPackageBackupError(
                packageInfo,
                NO_DATA,
                metadataOutputStream,
                BackupType.FULL
            )
        } just Runs
        coEvery { full.cancelFullBackup() } just Runs
        every { backendManager.backendProperties } returns safProperties
        every { settingsManager.useMeteredNetwork } returns false
        every { metadataOutputStream.close() } just Runs

        assertEquals(
            TRANSPORT_OK,
            backup.performFullBackup(packageInfo, fileDescriptor, 0)
        )
        assertEquals(
            DEFAULT_QUOTA_FULL_BACKUP,
            backup.getBackupQuota(packageInfo.packageName, true)
        )
        assertEquals(TRANSPORT_PACKAGE_REJECTED, backup.checkFullBackupSize(0))
        backup.cancelFullBackup()
        assertEquals(0L, backup.requestFullBackupTime())

        verify(exactly = 1) {
            metadataManager.onPackageBackupError(
                packageInfo,
                NO_DATA,
                metadataOutputStream,
                BackupType.FULL
            )
        }
        verify { metadataOutputStream.close() }
    }

    @Test
    fun `not allowed apps get their APKs backed up after @pm@ backup`() = runBlocking {
    }

    private fun expectApkBackupAndMetadataWrite() {
        coEvery { apkBackup.backupApkIfNecessary(packageInfo) } just Runs
        every { settingsManager.getToken() } returns token
        coEvery { backend.save(LegacyAppBackupFile.Metadata(token)) } returns metadataOutputStream
        every { metadataManager.onApkBackedUp(any(), packageMetadata) } just Runs
    }

}

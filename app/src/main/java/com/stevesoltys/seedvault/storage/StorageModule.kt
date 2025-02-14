/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.storage

import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.crypto.KeyManager
import org.calyxos.backup.storage.api.StorageBackup
import org.koin.dsl.module

val storageModule = module {
    single { StorageBackup(get(), { get<BackendManager>().backend }, get<KeyManager>()) }
}

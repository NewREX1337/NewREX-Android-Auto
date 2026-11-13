// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/**
 * NewREX'in yardımcı süreci ([ShellMain]) kendini buradan teslim eder. Sadece shell (ADB) ya da root yetkisindeki
 * süreç çağırabilir; başka uygulamalar çağırsa da hiçbir şey olmaz.
 */
class ShellProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != 2000 && uid != 0) return null
        if (method != "binder") return null
        val b = extras?.getBinder("binder") ?: return null
        DirectApps.attachOwn(b, extras.getInt("version"))
        return Bundle().apply { putBoolean("ok", true) }
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

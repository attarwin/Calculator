package com.mycalc.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

class VFile(val id: String, val name: String, val mime: String, val size: Long, val added: Long)

class VNote(val id: String, val title: String, val body: String, val updated: Long)

/** Everything in the vault is encrypted: file contents, file names, notes and the app list. */
object VaultStore {

    private fun sp(ctx: Context) = ctx.getSharedPreferences("vault_meta", Context.MODE_PRIVATE)

    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "vault")
        d.mkdirs()
        return d
    }

    private fun tmp(ctx: Context): File {
        val d = File(ctx.cacheDir, "vt")
        d.mkdirs()
        return d
    }

    private fun load(ctx: Context, key: String): JSONArray {
        val s = sp(ctx).getString(key, null) ?: return JSONArray()
        return try {
            JSONArray(VaultCrypto.decStr(s))
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun save(ctx: Context, key: String, a: JSONArray) {
        sp(ctx).edit().putString(key, VaultCrypto.encStr(a.toString())).apply()
    }

    // ------------------------------------------------------------ files

    fun files(ctx: Context): List<VFile> {
        val a = load(ctx, "files")
        val out = ArrayList<VFile>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out.add(VFile(o.getString("id"), o.getString("name"), o.getString("mime"), o.getLong("size"), o.getLong("added")))
        }
        return out
    }

    fun importFile(ctx: Context, uri: Uri): VFile? {
        var name = "file"
        var size = 0L
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (e: Exception) {
        }
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        val id = UUID.randomUUID().toString()
        val target = File(dir(ctx), "$id.enc")
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: return null
            input.use { inp ->
                FileOutputStream(target).use { out -> VaultCrypto.encryptStream(inp, out) }
            }
        } catch (e: Exception) {
            target.delete()
            return null
        }
        val f = VFile(id, name, mime, size, System.currentTimeMillis())
        val a = load(ctx, "files")
        val o = JSONObject()
        o.put("id", f.id)
        o.put("name", f.name)
        o.put("mime", f.mime)
        o.put("size", f.size)
        o.put("added", f.added)
        a.put(o)
        save(ctx, "files", a)
        return f
    }

    fun decryptToTemp(ctx: Context, f: VFile): File {
        clearTmp(ctx)
        val ext = f.name.substringAfterLast('.', "").filter { it.isLetterOrDigit() }.take(5)
        val out = File(tmp(ctx), if (ext.isEmpty()) f.id else f.id + "." + ext)
        FileInputStream(File(dir(ctx), f.id + ".enc")).use { inp ->
            FileOutputStream(out).use { o -> VaultCrypto.decryptStream(inp, o) }
        }
        return out
    }

    fun exportTo(ctx: Context, f: VFile, uri: Uri): Boolean {
        return try {
            val out = ctx.contentResolver.openOutputStream(uri) ?: return false
            out.use { o ->
                FileInputStream(File(dir(ctx), f.id + ".enc")).use { inp -> VaultCrypto.decryptStream(inp, o) }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun deleteFile(ctx: Context, id: String) {
        File(dir(ctx), "$id.enc").delete()
        val a = load(ctx, "files")
        val n = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.getString("id") != id) n.put(o)
        }
        save(ctx, "files", n)
    }

    fun clearTmp(ctx: Context) {
        try {
            tmp(ctx).listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------------------ notes

    fun notes(ctx: Context): List<VNote> {
        val a = load(ctx, "notes")
        val out = ArrayList<VNote>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out.add(VNote(o.getString("id"), o.getString("title"), o.getString("body"), o.getLong("updated")))
        }
        return out
    }

    fun saveNote(ctx: Context, id: String?, title: String, body: String) {
        val a = load(ctx, "notes")
        val newTitle = if (title.isNotBlank()) title.trim() else body.trim().lineSequence().firstOrNull()?.take(40).orEmpty().ifBlank { "Untitled" }
        val o = JSONObject()
        o.put("id", id ?: UUID.randomUUID().toString())
        o.put("title", newTitle)
        o.put("body", body)
        o.put("updated", System.currentTimeMillis())
        val n = JSONArray()
        var replaced = false
        for (i in 0 until a.length()) {
            val x = a.getJSONObject(i)
            if (id != null && x.getString("id") == id) {
                n.put(o)
                replaced = true
            } else {
                n.put(x)
            }
        }
        if (!replaced) n.put(o)
        save(ctx, "notes", n)
    }

    fun deleteNote(ctx: Context, id: String) {
        val a = load(ctx, "notes")
        val n = JSONArray()
        for (i in 0 until a.length()) {
            val x = a.getJSONObject(i)
            if (x.getString("id") != id) n.put(x)
        }
        save(ctx, "notes", n)
    }

    // ------------------------------------------------------------ quick-launch apps

    fun apps(ctx: Context): List<String> {
        val a = load(ctx, "apps")
        val out = ArrayList<String>()
        for (i in 0 until a.length()) out.add(a.getString(i))
        return out
    }

    fun addApp(ctx: Context, pkg: String) {
        val list = apps(ctx)
        if (list.contains(pkg)) return
        val a = JSONArray()
        for (p in list) a.put(p)
        a.put(pkg)
        save(ctx, "apps", a)
    }

    fun removeApp(ctx: Context, pkg: String) {
        val a = JSONArray()
        for (p in apps(ctx)) if (p != pkg) a.put(p)
        save(ctx, "apps", a)
    }
}

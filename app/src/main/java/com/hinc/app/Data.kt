package com.hinc.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Doc(val uri: String, val name: String, val cat: String, val t: Long, val mime: String)
data class Msg(val text: String, val t: Long)

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("hinc_claims", Context.MODE_PRIVATE)
    private fun str(k: String) = sp.getString(k, "") ?: ""
    var name: String
        get() = str("name")
        set(x) { sp.edit().putString("name", x).apply() }
    var dos: String
        get() = str("dos")
        set(x) { sp.edit().putString("dos", x).apply() }
    var cid: String
        get() = str("cid")
        set(x) { sp.edit().putString("cid", x).apply() }
    var plan: Int
        get() = sp.getInt("plan", -1)
        set(x) { sp.edit().putInt("plan", x).apply() }
    var pay: Int
        get() = sp.getInt("pay", -1)
        set(x) { sp.edit().putInt("pay", x).apply() }
    var stage: Int
        get() = sp.getInt("stage", 0)
        set(x) { sp.edit().putInt("stage", x).apply() }

    fun docs(): List<Doc> = runCatching {
        val a = JSONArray(sp.getString("docs", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Doc(o.getString("u"), o.getString("n"), o.getString("c"), o.getLong("t"), o.getString("m"))
        }
    }.getOrDefault(emptyList())

    fun saveDocs(l: List<Doc>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("u", it.uri).put("n", it.name).put("c", it.cat).put("t", it.t).put("m", it.mime)) }
        sp.edit().putString("docs", a.toString()).apply()
    }

    fun msgs(): List<Msg> = runCatching {
        val a = JSONArray(sp.getString("msgs", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Msg(o.getString("x"), o.getLong("t")) }
    }.getOrDefault(emptyList())

    fun saveMsgs(l: List<Msg>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("x", it.text).put("t", it.t)) }
        sp.edit().putString("msgs", a.toString()).apply()
    }
}

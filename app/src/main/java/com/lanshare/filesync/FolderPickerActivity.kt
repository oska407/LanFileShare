package com.lanshare.filesync

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import java.io.File

/** 简单的内置目录选择器（需要已获得文件访问权限） */
class FolderPickerActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "extra_path"
    }

    private lateinit var listView: ListView
    private lateinit var pathText: TextView
    private var current: File = Environment.getExternalStorageDirectory()
    private var dirs: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_picker)

        listView = findViewById(R.id.dirList)
        pathText = findViewById(R.id.pathText)
        val backBtn = findViewById<Button>(R.id.backBtn)
        val selectBtn = findViewById<Button>(R.id.selectBtn)
        val homeBtn = findViewById<Button>(R.id.homeBtn)

        homeBtn.setOnClickListener {
            current = Environment.getExternalStorageDirectory()
            refresh()
        }

        backBtn.setOnClickListener {
            val storageRoot = Environment.getExternalStorageDirectory().absolutePath
            val parent = current.parentFile
            if (parent != null && parent.absolutePath.startsWith(storageRoot)) {
                current = parent
                refresh()
            }
        }

        selectBtn.setOnClickListener {
            val i = Intent()
            i.putExtra(EXTRA_PATH, current.absolutePath)
            setResult(RESULT_OK, i)
            finish()
        }

        listView.setOnItemClickListener { _, _, pos, _ ->
            if (pos in dirs.indices) {
                current = dirs[pos]
                refresh()
            }
        }

        refresh()
    }

    private fun refresh() {
        pathText.text = current.absolutePath
        dirs = (current.listFiles { f -> f.isDirectory } ?: emptyArray())
            .filter { !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
        val names = dirs.map { "\uD83D\uDCC1 ${it.name}" } // 📁
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
    }
}

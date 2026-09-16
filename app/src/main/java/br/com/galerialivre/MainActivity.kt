package br.com.galerialivre

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Photo(val id: Long, val uri: Uri, val name: String, val album: String, val video: Boolean)

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<Uri?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incoming = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        setContent { Gallery(incoming) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming = intent.takeIf { it.action == Intent.ACTION_VIEW }?.data
    }
    private fun allowed(): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
        (Build.VERSION.SDK_INT >= 34 && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
    } else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private suspend fun loadPhotos(): List<Photo> = withContext(Dispatchers.IO) {
        val found = mutableListOf<Photo>()
        val cols = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
        contentResolver.query(MediaStore.Files.getContentUri("external"), cols,
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)",
            arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()),
            "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val type = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val album = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val video = c.getInt(type) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                found.add(Photo(c.getLong(id), ContentUris.withAppendedId(base, c.getLong(id)), c.getString(name) ?: "Arquivo", c.getString(album) ?: "Outros", video))
            }
        }
        found
    }

    @Composable private fun Gallery(external: Uri?) {
        var permission by remember { mutableStateOf(allowed()) }
        var photos by remember { mutableStateOf<List<Photo>>(emptyList()) }
        var selected by remember(external) { mutableStateOf<Uri?>(external) }
        var tab by remember { mutableIntStateOf(0) }
        var album by remember { mutableStateOf<String?>(null) }
        val favorites = remember { mutableStateListOf<String>() }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permission = allowed() }
        LaunchedEffect(permission) { if (permission) photos = loadPhotos() }
        MaterialTheme {
            Column(Modifier.fillMaxSize()) {
                Text("Galeria Livre", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
                if (selected != null) {
                    TextButton(onClick = { selected = null }) { Text("← Voltar") }
                    AsyncImage(model = selected, contentDescription = "Imagem", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().weight(1f))
                    photos.firstOrNull { it.uri == selected }?.let { item ->
                        Row(Modifier.padding(8.dp)) {
                            TextButton(onClick = { if (favorites.contains(item.uri.toString())) favorites.remove(item.uri.toString()) else favorites.add(item.uri.toString()) }) { Text(if (favorites.contains(item.uri.toString())) "Desfavoritar" else "Favoritar") }
                            TextButton(onClick = { startActivity(Intent(Intent.ACTION_SEND).apply { type = if (item.video) "video/*" else "image/*"; putExtra(Intent.EXTRA_STREAM, item.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) }) { Text("Compartilhar") }
                            if (item.video) TextButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(item.uri, "video/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) }) { Text("Reproduzir") }
                        }
                    }
                } else if (!permission) {
                    Text("Autorize o acesso às fotos e vídeos.", modifier = Modifier.padding(16.dp))
                    Button(onClick = { launcher.launch(if (Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)) }, modifier = Modifier.padding(16.dp)) { Text("Permitir acesso") }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf("Fotos", "Álbuns", "Vídeos", "Favoritos").forEachIndexed { index, name -> TextButton(onClick = { tab = index; album = null }) { Text(name) } }
                    }
                    if (tab == 1 && album == null) {
                        LazyVerticalGrid(columns = GridCells.Fixed(2)) {
                            items(photos.groupBy { it.album }.toList()) { (name, contents) ->
                                Column(Modifier.clickable { album = name }.padding(8.dp)) {
                                    AsyncImage(model = contents.first().uri, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(120.dp))
                                    Text("$name (${contents.size})")
                                }
                            }
                        }
                    } else {
                        if (album != null) TextButton(onClick = { album = null }) { Text("← Álbuns") }
                        val shown = photos.filter { when (tab) { 1 -> it.album == album; 2 -> it.video; 3 -> favorites.contains(it.uri.toString()); else -> !it.video } }
                        LazyVerticalGrid(columns = GridCells.Fixed(3)) {
                            items(shown, key = { it.uri.toString() }) { item ->
                                AsyncImage(model = item.uri, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.padding(2.dp).fillMaxWidth().aspectRatio(1f).clickable { selected = item.uri })
                            }
                        }
                    }
                }
            }
        }
    }
}

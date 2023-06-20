
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.streams.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import utils.Array
import utils.Object
import utils.String
import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.pathString

suspend fun downloadVideo(folder:File, teacherFile: File, pcFile: File, resourceId: String, type: Int = 0) {

    val info = runCatching {
        client.get(QUERY_VIDEO_INFO) {
            parameter("resourceId", resourceId)
        }.body<JsonObject>().Object("data")
    }.onFailure {}.getOrNull()
    if (info == null) {
        logOut("failed: $resourceId")
        return
    }

    println(info)

    val htmlFile =
        folder.apply { if (exists().not()) mkdirs() }.resolve("index.html")

    val videoInfos = info.Array("videoList")

    val teacherVideoInfo = videoInfos.single { it.String("videoName").contains("教师") }
    val pcVideoInfo = videoInfos.single { it.String("videoName").contains("HDMI") }

    val teacherUrl = Url(teacherVideoInfo.String("videoPath"))
    val pcUrl = Url(pcVideoInfo.String("videoPath"))

    supervisorScope {
        if (type == 0) {
            if (States.tasks.get(resourceId + "_1")?.isActive == true)
                return@supervisorScope
            States.tasks[resourceId + "_1"] = launch {
                runCatching {
                    downloadToFile(teacherFile, teacherUrl) { current, total, totalTime, time ->
                        if (total != -1L) {
                            val progress = current.times(1f).div(total)
                            States.progress[resourceId + "_1"] = progress

                            val speed = current.times(1f).div(totalTime).times(1000)

                            States.progressInfo[resourceId + "_1"] =
                                "%.2f%% %.2fMB/s".format(progress * 100, speed.div(UNIT_MB))
                        }
                    }
                }.onSuccess {
                    States.progress.remove(resourceId + "_1")
                    States.progressInfo.remove(resourceId + "_1")
                }.onFailure {
                    if (it is CancellationException) {
                        States.progressInfo.put(resourceId + "_1", "Cancelled")

                    } else {
                        States.progressInfo.put(resourceId + "_1", "Failed")
                        it.printStackTrace()
                    }
                }
            }
        } else if (type==1) {
            if (States.tasks.get(resourceId + "_2")?.isActive == true)
                return@supervisorScope
            States.tasks[resourceId + "_2"] = launch {
                runCatching {
                    downloadToFile(pcFile, pcUrl) { current, total, totalTime, time ->
                        if (total != -1L) {
                            val progress = current.times(1f).div(total)
                            States.progress[resourceId + "_2"] = progress
                            val speed = current.times(1f).div(totalTime).times(1000)
                            States.progressInfo[resourceId + "_2"] =
                                "%.2f%% %.2fMB/s".format(progress * 100, speed.div(UNIT_MB))
                        }
                    }
                }.onSuccess {
                    States.progress.remove(resourceId + "_2")
                    States.progressInfo.remove(resourceId + "_2")
                }.onFailure {
                    if (it is CancellationException) {
                        States.progressInfo.put(resourceId + "_2", "Cancelled")

                    } else {
                        States.progressInfo.put(resourceId + "_2", "Failed")
                        it.printStackTrace()
                    }
                }
            }
        }
    }
    //播放器
    val template=File("src/templates/play.html").readText()
    htmlFile.writeText(
        template
            .replace(
                "{HDMI}", pcFile.toRelativeString(folder)
            ).replace("{TEACHER}", teacherFile.toRelativeString(folder))
    )
}

suspend fun downloadToFile(
    finalFile: File,
    url: Url,
    onProgress: (current: Long, total: Long, totalTimeCost: Long, currentTimeCost: Long) -> Unit = { _, _, _, _ -> },

    ) {
    val tmpFile = finalFile.parentFile.resolve("${finalFile.name}.tmp")
    if (finalFile.exists()) return
    if (tmpFile.exists()) tmpFile.delete()
    withContext(Dispatchers.IO) {
        tmpFile.outputStream().use {
            client.prepareGet(url) {}.execute { httpResponse ->
                try {
                    it.receiveStream(httpResponse, finalFile, onProgress)
                } catch (e: CancellationException) {
                    tmpFile.delete()
                }
            }
        }
        tmpFile.renameTo(finalFile)
    }
}


private suspend fun FileOutputStream.receiveStream(
    httpResponse: HttpResponse,
    file: File,
    onProgress: (current: Long, total: Long, totalTimeCost: Long, currentTimeCost: Long) -> Unit = { _, _, _, _ -> },
) {
    val channel = httpResponse.bodyAsChannel()
    logOut("downloading ${(file.toPath()).normalize().pathString}")
    val contentLength = httpResponse.contentLength() ?: -1L
    val start = System.currentTimeMillis()
    withContext(Dispatchers.IO) {
        var last = start
        while (!channel.isClosedForRead) {
            last = System.currentTimeMillis()
            val packet = channel.readRemaining(DOWNLOAD_BUFFER_SIZE)
            writePacket(packet)
//            print(buildString {
//                append("write: ")
//                if (contentLength != -1L) {
//                    append("%.2f%%".format(channel.totalBytesRead.times(100.0).div(contentLength)))
//                } else {
//                    append("Nan%")
//                }
//                append(' ')
//                append(channel.totalBytesRead)
//                append('/')
//                append(contentLength)
//                append(' ')
//                val speed = channel.totalBytesRead.times(1f).div(System.currentTimeMillis() - start).times(1000)
//                append("%.2fMB/s".format(speed.div(UNIT_MB)))
//                append('\r')
//            })
            val now = System.currentTimeMillis()
            onProgress(channel.totalBytesRead, contentLength, now - start, now - last)
        }
    }
    logOut()
    logOut("downloaded ${file.toPath()}.normalize().pathString}")
}

enum class PageState {
    INDEX,
    SETTINGS
}
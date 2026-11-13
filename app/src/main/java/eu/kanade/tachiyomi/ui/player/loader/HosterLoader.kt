package eu.kanade.tachiyomi.ui.player.loader

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.getChangedAt
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.coroutines.cancellation.CancellationException

class HosterLoader {
    companion object {
        private fun getTargetQuality(): String {
            val context = Injekt.get<Application>()
            val playerPreferences = Injekt.get<PlayerPreferences>()
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)

            val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true

            return if (isWifi) {
                playerPreferences.defaultQualityWifi().get()
            } else {
                playerPreferences.defaultQualityMobile().get()
            }
        }

        private fun Video.matchesQuality(targetQuality: String): Boolean {
            val targetInt = targetQuality.toIntOrNull() ?: return false
            return resolution == targetInt ||
                videoTitle.contains("${targetQuality}p", ignoreCase = true)
        }

        /**
         * Check for the best video from the current hosterState.
         *
         * Checks user's preferred Wi-Fi/Mobile quality first (if not "auto"),
         * then falls back to `preferred`, then the first video with a non-empty url.
         *
         * @return the indices of the hoster & video
         */
        fun selectBestVideo(hosterState: List<HosterState>): Pair<Int, Int> {
            val targetQuality = getTargetQuality()
            val availableHosters = hosterState.withIndex()
                .filter { (_, state) -> state is HosterState.Ready }

            // 1. If user configured a specific resolution for the active network, try matching it first
            if (targetQuality != "auto") {
                val isQualityMatch: (Pair<Video, Video.State>) -> Boolean = { (v, s) ->
                    v.matchesQuality(targetQuality) && (s == Video.State.READY || s == Video.State.QUEUE)
                }

                // Prefer a hoster marked preferred that also matches the target quality
                val prefQualityHosterIdx = availableHosters.indexOfFirst {
                    (it.value as HosterState.Ready).let { hoster ->
                        hoster.videoList zip hoster.videoState
                    }.any { (v, s) -> v.preferred && isQualityMatch(v to s) }
                }
                if (prefQualityHosterIdx != -1) {
                    val videoList = (availableHosters[prefQualityHosterIdx].value as HosterState.Ready).let { hoster ->
                        hoster.videoList zip hoster.videoState
                    }
                    val videoIdx = videoList.indexOfFirst { (v, s) -> v.preferred && isQualityMatch(v to s) }
                    return availableHosters[prefQualityHosterIdx].index to videoIdx
                }

                // Otherwise pick any hoster offering the target quality
                val anyQualityHosterIdx = availableHosters.indexOfFirst {
                    (it.value as HosterState.Ready).let { hoster ->
                        hoster.videoList zip hoster.videoState
                    }.any(isQualityMatch)
                }
                if (anyQualityHosterIdx != -1) {
                    val videoList = (availableHosters[anyQualityHosterIdx].value as HosterState.Ready).let { hoster ->
                        hoster.videoList zip hoster.videoState
                    }
                    val videoIdx = videoList.indexOfFirst(isQualityMatch)
                    return availableHosters[anyQualityHosterIdx].index to videoIdx
                }
            }

            // 2. Fallback: Check for first preferred
            val isPreferred: (Pair<Video, Video.State>) -> Boolean = { (v, s) ->
                v.preferred && (s == Video.State.READY || s == Video.State.QUEUE)
            }
            val prefHosterIdx = availableHosters.indexOfFirst {
                (it.value as HosterState.Ready).let { hoster ->
                    hoster.videoList zip hoster.videoState
                }.any(isPreferred)
            }
            if (prefHosterIdx != -1) {
                val videoList = (availableHosters[prefHosterIdx].value as HosterState.Ready).let { hoster ->
                    hoster.videoList zip hoster.videoState
                }
                val prefVideoIdx = videoList.indexOfFirst(isPreferred)
                return availableHosters[prefHosterIdx].index to prefVideoIdx
            }

            // 3. Fallback: Check for first video with non-empty url
            val firstValid: (Pair<Video, Video.State>) -> Boolean = { (v, s) ->
                v.videoUrl.isNotEmpty() && (s == Video.State.READY || s == Video.State.QUEUE)
            }
            val firstAvailableHosterIdx = availableHosters.indexOfFirst {
                (it.value as HosterState.Ready).let { hoster ->
                    hoster.videoList zip hoster.videoState
                }.any(firstValid)
            }
            if (firstAvailableHosterIdx != -1) {
                val videoList = (availableHosters[firstAvailableHosterIdx].value as HosterState.Ready).let { hoster ->
                    hoster.videoList zip hoster.videoState
                }
                val firstVideoIdx = videoList.indexOfFirst(firstValid)
                return availableHosters[firstAvailableHosterIdx].index to firstVideoIdx
            }

            // No success
            return Pair(-1, -1)
        }

        class EarlyReturnException(val video: Video) : Exception()

        /**
         * Return the first loaded and valid "best" video, based on the criteria in the function `selectBestVideo` above.
         *
         * @param source The source for the episode
         * @param hosterList the list of hosters
         * @return the video, or null if no valid video was found
         */
        suspend fun getBestVideo(source: AnimeSource, hosterList: List<Hoster>): Video? {
            val hosterStates = MutableList<HosterState>(hosterList.size) { HosterState.Idle("") }
            val targetQuality = getTargetQuality()

            return try {
                withContext(Dispatchers.IO) {
                    hosterList.mapIndexed { hosterIdx, hoster ->
                        async {
                            val hosterState = EpisodeLoader.loadHosterVideos(source, hoster)
                            hosterStates[hosterIdx] = hosterState

                            if (hosterState is HosterState.Ready) {
                                val prefIndex = hosterState.videoList.indexOfFirst { video ->
                                    val matchesPref = if (targetQuality == "auto") {
                                        video.preferred
                                    } else {
                                        video.matchesQuality(targetQuality)
                                    }
                                    matchesPref && !video.initialized
                                }
                                if (prefIndex != -1) {
                                    val video = hosterState.videoList[prefIndex]
                                    hosterStates[hosterIdx] =
                                        (hosterStates[hosterIdx] as HosterState.Ready).getChangedAt(
                                            prefIndex,
                                            video,
                                            Video.State.LOAD_VIDEO,
                                        )

                                    val resolvedVideo = getResolvedVideo(source, video)
                                    if (resolvedVideo?.videoUrl?.isNotEmpty() == true) {
                                        coroutineContext.cancelChildren()
                                        throw EarlyReturnException(resolvedVideo)
                                    }

                                    hosterStates[hosterIdx] =
                                        (hosterStates[hosterIdx] as HosterState.Ready).getChangedAt(
                                            prefIndex,
                                            video,
                                            Video.State.ERROR,
                                        )
                                }
                            }
                        }
                    }.awaitAll()

                    var (hosterIdx, videoIdx) = selectBestVideo(hosterStates)
                    while (hosterIdx != -1) {
                        val hosterState = hosterStates[hosterIdx] as HosterState.Ready
                        val video = hosterState.videoList[videoIdx]
                        hosterStates[hosterIdx] =
                            (hosterStates[hosterIdx] as HosterState.Ready).getChangedAt(
                                videoIdx,
                                video,
                                Video.State.LOAD_VIDEO,
                            )

                        val resolvedVideo = getResolvedVideo(source, video)
                        if (resolvedVideo?.videoUrl?.isNotEmpty() == true) {
                            coroutineContext.cancelChildren()
                            return@withContext resolvedVideo
                        }

                        hosterStates[hosterIdx] =
                            (hosterStates[hosterIdx] as HosterState.Ready).getChangedAt(
                                videoIdx,
                                video,
                                Video.State.ERROR,
                            )
                        val newResult = selectBestVideo(hosterStates)
                        hosterIdx = newResult.first
                        videoIdx = newResult.second
                    }

                    coroutineContext.cancelChildren()
                    return@withContext null
                }
            } catch (e: EarlyReturnException) {
                e.video
            }
        }

        suspend fun getResolvedVideo(source: AnimeSource?, video: Video): Video? {
            val resolvedVideo = if (source is AnimeHttpSource && !video.initialized) {
                try {
                    source.resolveVideo(video)
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        throw e
                    }

                    null
                }
            } else {
                video
            }

            return resolvedVideo?.copy(initialized = true)
        }
    }
}
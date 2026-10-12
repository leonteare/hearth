// How Room stores enum columns (download and request states) as text.
package im.flume.hearth.data

import androidx.room.TypeConverter
import im.flume.hearth.spoken.SpokenDownloadState

class Converters {
    @TypeConverter fun fromState(s: DownloadState): String = s.name
    @TypeConverter fun toState(s: String): DownloadState = DownloadState.valueOf(s)
    @TypeConverter fun fromRequestStatus(s: RequestStatus): String = s.name
    @TypeConverter fun toRequestStatus(s: String): RequestStatus =
        RequestStatus.entries.firstOrNull { it.name == s } ?: RequestStatus.FAILED
    @TypeConverter fun fromSpokenState(s: SpokenDownloadState): String = s.name
    @TypeConverter fun toSpokenState(s: String): SpokenDownloadState =
        SpokenDownloadState.entries.firstOrNull { it.name == s } ?: SpokenDownloadState.FAILED
}

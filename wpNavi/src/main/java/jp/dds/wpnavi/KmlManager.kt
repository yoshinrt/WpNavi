package jp.dds.wpnavi

import android.util.Log
import android.util.Xml
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.PolylineOptions
import jp.dds.dds_lib.BuildConfig
import org.xmlpull.v1.XmlPullParser
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class KmlManager {
    @JvmField
    var Points: IntArray

    internal constructor() {
        Points = IntArray(0)
    }

    internal constructor(ary: IntArray) {
        Points = ary
    }

    fun GetPoint(idx: Int): LatLng {
        return LatLng(GetLat(idx), GetLng(idx))
    }

    fun GetLng(idx: Int): Double {
        return Points[idx * 2] / ToInt // lng
    }

    fun GetLat(idx: Int): Double {
        return Points[idx * 2 + 1] / ToInt // lat
    }

    fun Size(): Int {
        return Points.size / 2
    }

    fun DistancePow2(iIdx: Int, dLong0: Double, dLati0: Double): Double {
        return DistancePow2(GetLng(iIdx), GetLat(iIdx), dLong0, dLati0)
    }

    fun Distance(iIdx: Int, dLong0: Double, dLati0: Double): Double {
        return Distance(GetLng(iIdx), GetLat(iIdx), dLong0, dLati0)
    }

    fun InDistance(
        iDistance: Int,
        dLong0: Double, dLati0: Double,
        dLong1: Double, dLati1: Double
    ): Boolean {
        val iLatDist: Int = (abs(dLati0 - dLati1) * 110949.75926813729).toInt()
        if (iLatDist > iDistance) return false

        val iLngDist: Int =
            (abs(dLong0 - dLong1) * cos(dLati0 * (Math.PI / 180)) * 111448.44724952266).toInt()
        if (iLngDist > iDistance) return false

        return iDistance * iDistance >= iLatDist * iLatDist + iLngDist * iLngDist
    }

    fun InDistance(
        iDistance: Int, iIdx: Int,
        dLong0: Double, dLati0: Double
    ): Boolean {
        return InDistance(iDistance, GetLng(iIdx), GetLat(iIdx), dLong0, dLati0)
    }

    fun Swap(i: Int, j: Int) {
        if (i == j) return

        val iLng = Points[i * 2]
        val iLat = Points[i * 2 + 1]

        Points[i * 2] = Points[j * 2]
        Points[i * 2 + 1] = Points[j * 2 + 1]
        Points[j * 2] = iLng
        Points[j * 2 + 1] = iLat
    }

    inner class KmlInfo {
        @JvmField var m_strTitle: String? = null
        @JvmField var m_dMinLng: Double = 1000.0
        @JvmField var m_dMinLat: Double = 1000.0
        @JvmField var m_dMaxLng: Double = -1000.0
        @JvmField var m_dMaxLat: Double = -1000.0
        @JvmField var m_Polyline: PolylineOptions = PolylineOptions()
        @JvmField var m_iErrorCode: Int = 0
    }

    fun LoadKML(strKmlFile: String?, iMinDistance: Int): KmlInfo {
        var iState: Int
        val Info = KmlInfo()

        if (strKmlFile == null) {
            Info.m_iErrorCode = R.string.text_FileNotFound
            return Info
        }

        var zfIn: ZipFile? = null
        var fsIn: InputStream? = null

        try {
            // KMZ を開いてみる
            zfIn = ZipFile(strKmlFile)

            val enumulation = zfIn.entries()
            while (enumulation.hasMoreElements()) {
                val entry = enumulation.nextElement()
                if (entry.isDirectory) {
                    continue
                }

                if (bDebug) Log.d("WpNavi", "LoadKML:ZipEntry:" + entry.name)
                if (entry.name.endsWith(".kml")) {
                    fsIn = zfIn.getInputStream(entry)
                    break
                }
            }
            // kmz 中に kml がなかった
            if (fsIn == null) {
                zfIn.close()
                Info.m_iErrorCode = R.string.text_FileNotFound
                return Info
            }
        } catch (e: IOException) {
            // KMZ で失敗したので，KML を開く
            if (zfIn != null) try {
                zfIn.close()
            } catch (e2: IOException) {
            }

            try {
                fsIn = FileInputStream(strKmlFile)
            } catch (e1: FileNotFoundException) {
                Info.m_iErrorCode = R.string.text_FileNotFound
                return Info
            }
        }

        val xpp = Xml.newPullParser()

        iState = KML_NONE

        val Point = DoubleArray(2)
        val TmpPoints = ArrayList<Int>()

        try {
            xpp.setInput(fsIn, "UTF-8")

            var str: String
            var iType = xpp.eventType
            while (iType != XmlPullParser.END_DOCUMENT) {
                when (iType) {
                    XmlPullParser.START_TAG -> {
                        str = xpp.name

                        if (str == "LineString") {
                            iState = iState or KML_LINESTRING
                        } else if (str == "Point") {
                            iState = iState or KML_POINT
                        } else if (str == "coordinates") {
                            iState = iState or KML_COORDINATES
                        } else if (Info.m_strTitle == null && str == "name") {
                            Info.m_strTitle = xpp.nextText()
                        }
                    }

                    XmlPullParser.TEXT -> if ((iState and KML_COORDINATES) != 0) {
                        str = xpp.text

                        if ((iState and KML_POINT) != 0) {
                            // 経由地
                            ParseCoordinate(str, Info, Point)
                            if (TmpPoints.size == 0 ||
                                !InDistance(
                                    iMinDistance,
                                    TmpPoints[TmpPoints.size - 2] / ToInt,
                                    TmpPoints[TmpPoints.size - 1] / ToInt,
                                    Point[0], Point[1]
                                )
                            ) {
                                TmpPoints.add((Point[0] * ToInt).toInt())
                                TmpPoints.add((Point[1] * ToInt).toInt())
                            }
                        } else if ((iState and KML_LINESTRING) != 0) {
                            // ルート
                            var c1 = 0
                            do {
                                // 空白のサーチ
                                var c2 = c1
                                while (c2 < str.length) {
                                    if (str[c2] <= ' ') break
                                    ++c2
                                }

                                if (c1 != c2) {
                                    ParseCoordinate(str.substring(c1, c2), Info, Point)
                                    Info.m_Polyline.add(LatLng(Point[1], Point[0]))
                                }
                                c1 = c2 + 1
                            } while (c1 < str.length)
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        str = xpp.name
                        if (str == "LineString") {
                            iState = iState and KML_LINESTRING.inv()
                        } else if (str == "Point") {
                            iState = iState and KML_POINT.inv()
                        } else if (str == "coordinates") {
                            iState = iState and KML_COORDINATES.inv()
                        }
                    }
                }
                iType = xpp.next()
            }
        } catch (e: Exception) {
            Info.m_iErrorCode = R.string.text_InvalidKMLFormat
            try {
                fsIn!!.close()
            } catch (e2: IOException) {
            }
            return Info
        }

        // close
        try {
            fsIn!!.close()
        } catch (e: IOException) {
        }

        // 一応数チェック
        if (TmpPoints.size == 0 || Info.m_Polyline.points.size == 0) {
            Info.m_iErrorCode = R.string.text_InvalidKMLFormat
            return Info
        }

        // ここまで来たらロード成功
        Points = IntArray(TmpPoints.size)
        for (i in TmpPoints.indices) {
            Points[i] = TmpPoints[i]
        }

        // WP を PolyLine にそってソートする
        SortWp(Info.m_Polyline.points)

        return Info
    }

    private fun ParseCoordinate(str: String, Info: KmlInfo, Point: DoubleArray) {
        var c1: Int
        var c2: Int = 0
        if ((str.indexOf(',').also { c1 = it }) >= 0 &&
            (str.indexOf(',', c1 + 1).also { c2 = it }) >= 0
        ) {
            Point[0] = str.substring(0, c1).toDouble()
            Point[1] = str.substring(c1 + 1, c2).toDouble()

            if (Info.m_dMinLng > Point[0]) Info.m_dMinLng = Point[0]
            if (Info.m_dMaxLng < Point[0]) Info.m_dMaxLng = Point[0]
            if (Info.m_dMinLat > Point[1]) Info.m_dMinLat = Point[1]
            if (Info.m_dMaxLat < Point[1]) Info.m_dMaxLat = Point[1]
        }
    }

    private fun SortWp(Line: List<LatLng>) {
        // 原点
        val dLng0 = Line[0].longitude
        val dLat0 = Line[0].latitude

        // 簡易 x,y 変換用のパラメータ
        val dLng2Meter = Distance(
            dLng0, dLat0, dLng0 + 1.0 / 3600, dLat0
        ) * 3600

        val dLat2Meter = Distance(
            dLng0, dLat0, dLng0, dLat0 + 1.0 / 3600
        ) * 3600

        // WP を x,y 変換
        val iWpX = IntArray(Size())
        val iWpY = IntArray(Size())

        for (i in 0 until Size()) {
            iWpX[i] = ((GetLng(i) - dLng0) * dLng2Meter).toInt()
            iWpY[i] = ((GetLat(i) - dLat0) * dLat2Meter).toInt()
        }

        var x0: Int
        var y0: Int
        var x1 = 0
        var y1 = 0
        var iSortedIdx = 0

        var iIdxLine = 0
        while (iIdxLine < Line.size - 1 && iSortedIdx < Size() - 1) {
            x0 = x1
            y0 = y1
            x1 = ((Line[iIdxLine + 1].longitude - dLng0) * dLng2Meter).toInt()
            y1 = ((Line[iIdxLine + 1].latitude - dLat0) * dLat2Meter).toInt()

            val x01 = x0 - x1
            val y01 = y0 - y1

            for (iIdxWp in iSortedIdx until Size()) {
                val xp0 = iWpX[iIdxWp] - x0
                val yp0 = iWpY[iIdxWp] - y0
                val xp1 = iWpX[iIdxWp] - x1
                val yp1 = iWpY[iIdxWp] - y1

                // 線分端点と 1000m 離れているので online 判定スキップ
                if ((abs(xp0) > iDistTh || abs(yp0) > iDistTh) &&
                    (abs(xp1) > iDistTh || abs(yp1) > iDistTh)
                ) continue

                // L1<-L0 と Wp<-L0 がなす角が 90度以上なら，距離は L0～Wp となる
                if (-x01 * xp0 - y01 * yp0 <= 0) {
                    if (xp0 * xp0 + yp0 * yp0 <= iOnlineDistPow2) {
                        if (bDebug) Log.d(
                            "WpNavi", String.format(
                                "WpSortP[%d]: %d<->%d, %f",
                                iIdxLine,
                                iSortedIdx,
                                iIdxWp,
                                sqrt((xp0 * xp0 + yp0 * yp0).toDouble())
                            )
                        )
                        Swap(iSortedIdx, iIdxWp, iWpX, iWpY)
                        ++iSortedIdx
                        break
                    }
                } else {
                    if (x01 * xp1 + y01 * yp1 >= 0 &&
                        abs(x01 * yp1 - y01 * xp1) <= iOnlineDist * sqrt((x01 * x01 + y01 * y01).toDouble()).toInt()
                    ) {
                        if (bDebug) Log.d(
                            "WpNavi", String.format(
                                "WpSortL[%d]: %d<->%d, %f",
                                iIdxLine,
                                iSortedIdx,
                                iIdxWp,
                                abs(x01 * yp1 - y01 * xp1) / sqrt((x01 * x01 + y01 * y01).toDouble())
                            )
                        )
                        Swap(iSortedIdx, iIdxWp, iWpX, iWpY)
                        ++iSortedIdx
                        break
                    }
                }
            }
            ++iIdxLine
        }
    }

    private fun Swap(i: Int, j: Int, iWpX: IntArray, iWpY: IntArray) {
        Swap(i, j)
        val x = iWpX[i]
        iWpX[i] = iWpX[j]
        iWpX[j] = x
        val y = iWpY[i]
        iWpY[i] = iWpY[j]
        iWpY[j] = y
    }

    companion object {
        @JvmField
        val bDebug: Boolean = BuildConfig.DEBUG

        private const val ToInt = 1E7

        private const val _a = 6378137.000
        private const val _b = 6356752.314245
        private const val _e2 = (_a * _a - _b * _b) / (_a * _a)
        private const val ToRAD = Math.PI / 180

        @JvmStatic
        fun DistancePow2(
            dLong0: Double, dLati0: Double,
            dLong1: Double, dLati1: Double
        ): Double {
            // ヒュベニの公式
            val dx = (dLong1 - dLong0) * ToRAD
            val dy = (dLati1 - dLati0) * ToRAD
            val uy = (dLati0 + dLati1) / 2 * ToRAD
            val W: Double = sqrt(1 - _e2 * sin(uy) * sin(uy))
            val M: Double = _a * (1 - _e2) / W.pow(3)
            val N = _a / W

            return dy * dy * M * M + (dx * N * cos(uy)).pow(2)
        }

        @JvmStatic
        fun Distance(
            dLong0: Double, dLati0: Double,
            dLong1: Double, dLati1: Double
        ): Double {
            return sqrt(DistancePow2(dLong0, dLati0, dLong1, dLati1))
        }

        /*** Load KML  */
        private const val KML_NONE = 0
        private const val KML_POINT = 1 shl 0
        private const val KML_LINESTRING = 1 shl 1
        private const val KML_COORDINATES = 1 shl 2

        /*** WP を PolyLine にそってソートする  */
        private const val iOnlineDist = 5
        private const val iOnlineDistPow2 = iOnlineDist * iOnlineDist

        // ルート線分の端点からこれだけ離れている WP は online 判定から除外
        private const val iDistTh = 1000 // [m]
    }
}

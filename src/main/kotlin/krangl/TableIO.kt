//@file:Suppress("unused")

package krangl

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.apache.commons.csv.CSVRecord
import java.io.*
import java.net.URI
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
Methods to read and write tables into/from DataFrames
 * see also https://commons.apache.org/proper/commons-csv/ for other implementations
 * https://github.com/databricks/spark-csv
 * https://examples.javacodegeeks.com/core-java/apache/commons/csv-commons/writeread-csv-files-with-apache-commons-csv-example/

 */


enum class ColType {
    Int, Double, Boolean, String, Guess
}

private fun asStream(fileOrUrl: String) = (if (isURL(fileOrUrl)) {
    URL(fileOrUrl).toURI()
} else {
    File(fileOrUrl).toURI()
}).toURL().openStream()

internal fun isURL(fileOrUrl: String): Boolean = listOf("http:", "https:", "ftp:").any { fileOrUrl.startsWith(it) }


@JvmOverloads fun DataFrame.Companion.readCSV(
    fileOrUrl: String,
    format: CSVFormat = CSVFormat.DEFAULT.withHeader(),
    colTypes: Map<String, ColType> = mapOf()
) = readDelim(
    asStream(fileOrUrl),
    format = format,
    colTypes = colTypes,
    isCompressed = listOf("gz", "zip").contains(fileOrUrl.split(".").last())
)


@JvmOverloads fun DataFrame.Companion.readTSV(
    fileOrUrl: String,
    format: CSVFormat = CSVFormat.DEFAULT.withHeader(),
    colTypes: Map<String, ColType> = mapOf()
) = readDelim(
    inStream = asStream(fileOrUrl),
    format = format,
    colTypes = colTypes,
    isCompressed = listOf("gz", "zip").contains(fileOrUrl.split(".").last())
)

@JvmOverloads fun DataFrame.Companion.readTSV(
    file: File,
    format: CSVFormat = CSVFormat.TDF.withHeader(),
    colTypes: Map<String, ColType> = mapOf()
) = readDelim(
    FileInputStream(file),
    format = format,
    colTypes = colTypes,
    isCompressed = guessCompressed(file)
)


@JvmOverloads fun DataFrame.Companion.readCSV(
    file: File,
    format: CSVFormat = CSVFormat.DEFAULT.withHeader(),
    colTypes: Map<String, ColType> = mapOf()
) = readDelim(
    inStream = FileInputStream(file),
    format = format,
    colTypes = colTypes,
    isCompressed = listOf("gz", "zip").contains(file.extension)
)


private fun guessCompressed(file: File) = listOf("gz", "zip").contains(file.extension)


// http://stackoverflow.com/questions/9648811/specific-difference-between-bufferedreader-and-filereader
fun DataFrame.Companion.readDelim(
    uri: URI,
    //                                hasHeader:Boolean =true,
    format: CSVFormat = CSVFormat.DEFAULT.withHeader(),
    isCompressed: Boolean = uri.toURL().toString().endsWith(".gz"),
    colTypes: Map<String, ColType> = mapOf()
): DataFrame {

    val inputStream = uri.toURL().openStream()
    val streamReader = if (isCompressed) {
        // http://stackoverflow.com/questions/1080381/gzipinputstream-reading-line-by-line
        val gzip = GZIPInputStream(inputStream)
        InputStreamReader(gzip)
    } else {
        InputStreamReader(inputStream)
    }

    return readDelim(
        BufferedReader(streamReader),
        format = format,
        colTypes = colTypes
    )
}

//http://stackoverflow.com/questions/5200187/convert-inputstream-to-bufferedreader
fun DataFrame.Companion.readDelim(
    inStream: InputStream,
    format: CSVFormat = CSVFormat.DEFAULT.withHeader(),
    isCompressed: Boolean = false,
    colTypes: Map<String, ColType> = mapOf()
) =
    if (isCompressed) {
        InputStreamReader(GZIPInputStream(inStream))
    } else {
        BufferedReader(InputStreamReader(inStream, "UTF-8"))
    }.run {
        readDelim(this, format, colTypes = colTypes)
    }


fun DataFrame.Companion.readDelim(
    reader: Reader,
    format: CSVFormat = CSVFormat.DEFAULT.withHeader(),
    colTypes: Map<String, ColType> = mapOf()
): DataFrame {

    val formatWithNullString = if(format.isNullStringSet) {
        format
    } else {
        format.withNullString(MISSING_VALUE)
    }

    val csvParser = formatWithNullString.parse(reader)
    val records = csvParser.records

    val columnNames = csvParser.headerMap?.keys
        ?: (1..csvParser.records[0].count()).mapIndexed { index, _ -> "X${index}" }

    // Make column names unique when reading them + unit test
    val uniqueNames = columnNames
        .withIndex()
        .groupBy { it.value }
        .flatMap { (grpName, columns) ->
            columns
                .mapIndexed { index, indexedValue ->
                    indexedValue.index to (grpName + "_${index + 2}")
                }
        }
        .sortedBy { it.first }.map { it.second }


    //    csvParser.headerMap.keys.pmap{colName ->
    val cols = columnNames.map { colName ->
        val defaultColType = colTypes[".default"] ?: ColType.Guess

        val colType = colTypes[colName] ?: defaultColType

        dataColFactory(colName, colType, records)
    }

    return SimpleDataFrame(cols)
}


val MISSING_VALUE = "NA"

internal fun String?.nullAsNA(): String = this ?: MISSING_VALUE

internal fun String?.cellValueAsBoolean(): Boolean? {
    if (this == null) return null

    var cellValue: String? = toUpperCase()

    cellValue = if (cellValue == "F") "FALSE" else cellValue
    cellValue = if (cellValue == "T") "TRUE" else cellValue

    if (!listOf("TRUE", "FALSE", null).contains(cellValue)) throw NumberFormatException("invalid boolean cell value")

    return cellValue?.toBoolean()
}

internal fun guessColType(firstElements: List<String?>): ColType =
    when {
        isBoolCol(firstElements) -> ColType.Boolean
        isIntCol(firstElements) -> ColType.Int
        isDoubleCol(firstElements) -> ColType.Double
        else -> ColType.String
    }


internal fun dataColFactory(colName: String, colType: ColType, records: MutableList<CSVRecord>): DataCol =
    when (colType) {
    // see https://github.com/holgerbrandl/krangl/issues/10
        ColType.Int -> try {
            IntCol(colName, records.map { it[colName]?.toInt() })
        } catch (e: NumberFormatException) {
            StringCol(colName, records.map { it[colName] })
        }

        ColType.Double -> DoubleCol(colName, records.map { it[colName]?.toDouble() })

        ColType.Boolean -> BooleanCol(colName, records.map { it[colName]?.cellValueAsBoolean() })

        ColType.String -> StringCol(colName, records.map { it[colName] })

        ColType.Guess -> dataColFactory(colName, guessColType(peekCol(colName, records)), records)

    }


// TODO add missing value support with user defined string (e.g. NA here) here

internal fun isDoubleCol(firstElements: List<String?>): Boolean = try {
    firstElements.map { it?.toDouble() }; true
} catch (e: NumberFormatException) {
    false
}

internal fun isIntCol(firstElements: List<String?>): Boolean = try {
    firstElements.map { it?.toInt() }; true
} catch (e: NumberFormatException) {
    false
}

internal fun isBoolCol(firstElements: List<String?>): Boolean = try {
    firstElements.map { it?.cellValueAsBoolean() }; true
} catch (e: NumberFormatException) {
    false
}


internal fun peekCol(colName: String?, records: List<CSVRecord>, peekSize: Int = 5): List<String?> {
    val result = mutableListOf<String>()
    for(element in records) {
        if(result.size == peekSize) return result
        if(element[colName] != null) result.add(element[colName])
    }
    return result
}


fun DataFrame.writeCSV(
    file: File,
    format: CSVFormat = CSVFormat.DEFAULT.withHeader(*names.toTypedArray())
) {
    val format = if (format.run { header != null && header.size == 0 }) {
        warning("[krangl] Adding missing column name to csv format")
        format.withHeader(*names.toTypedArray())
    } else {
        format
    }

    val compress: Boolean = listOf("gz", "zip").contains(file.extension)

    val p = if (!compress) PrintWriter(file) else BufferedWriter(OutputStreamWriter(GZIPOutputStream(FileOutputStream(file))))

    //initialize CSVPrinter object
    val csvFilePrinter = CSVPrinter(p, format)

    // write records
    for (record in rowData()) {
        csvFilePrinter.printRecord(record)
    }

    p.flush()
    p.close()
}


/**
An example data frame with 83 rows and 11 variables

This is an updated and expanded version of the mammals sleep dataset. Updated sleep times and weights were taken from V. M. Savage and G. B. West. A quantitative, theoretical framework for understanding mammalian sleep. Proceedings of the National Academy of Sciences, 104 (3):1051-1056, 2007.

Additional variables order, conservation status and vore were added from wikipedia.
- name. common name
- genus.
- vore. carnivore, omnivore or herbivore?
- order.
- conservation. the conservation status of the animal
- sleep\_total. total amount of sleep, in hours
- sleep\_rem. rem sleep, in hours
- sleep\_cycle. length of sleep cycle, in hours
- awake. amount of time spent awake, in hours
- brainwt. brain weight in kilograms
- bodywt. body weight in kilograms
 */
val sleepData by lazy { DataFrame.readDelim(DataFrame::class.java.getResourceAsStream("data/msleep.csv")) }


/* Data class required to parse sleep Data records. */
data class SleepPattern(
    val name: String,
    val genus: String,
    val vore: String?,
    val order: String,
    val conservation: String?,
    val sleep_total: Double,
    val sleep_rem: Double?,
    val sleep_cycle: Double?,
    val awake: Double,
    val brainwt: Double?,
    val bodywt: Double
)

val sleepPatterns by lazy {
    sleepData.rows.map { row ->
        SleepPattern(
            row["name"] as String,
            row["genus"] as String,
            row["vore"] as String?,
            row["order"] as String,
            row["conservation"] as String?,
            row["sleep_total"] as Double,
            row["sleep_rem"] as Double?,
            row["sleep_cycle"] as Double?,
            row["awake"] as Double,
            row["brainwt"] as Double?,
            row["bodywt"] as Double
        )
    }
}


val irisData = DataFrame.readDelim(DataFrame::class.java.getResourceAsStream("data/iris.txt"), format = CSVFormat.TDF.withHeader())


/**
On-time data for all 336776 flights that departed NYC (i.e. JFK, LGA or EWR) in 2013.

Adopted from r, see `nycflights13::flights`
 */


internal val cacheDataDir by lazy {
    File(System.getProperty("user.home"), ".krangl_example_data").apply { if (!isDirectory()) mkdir() }
}

internal val flightsCacheFile = File(cacheDataDir, ".flights_data.tsv.gz")


val flightsData by lazy {

    if (!flightsCacheFile.isFile) {
        warning("[krangl] Downloading flights data into local cache...", false)
        val flightsURL = URL("https://github.com/holgerbrandl/krangl/blob/v0.4/src/test/resources/krangl/data/nycflights.tsv.gz?raw=true")
        warning("Done!")


        //    for progress monitoring use
        //    https@ //stackoverflow.com/questions/12800588/how-to-calculate-a-file-size-from-url-in-java

        flightsCacheFile.writeBytes(flightsURL.readBytes())
    }


    DataFrame.readTSV(flightsCacheFile)

    // consider to use progress bar here
}

// todo support Read and write data using Tablesaw’s “.saw” format --> use dedicated artifact to minimize dependcies
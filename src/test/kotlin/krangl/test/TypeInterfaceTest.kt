package krangl.test;

import io.kotlintest.matchers.shouldBe
import krangl.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * @author Holger Brandl
 */
class TypeInterfaceTest {


    val users = dataFrameOf(
        "firstName", "lastName", "age", "hasSudo")(
        "max", "smith", 53, false,
        "tom", "doe", 30, false,
        "eva", "miller", 23, true,
        null, "meyer", 23, null
    )

    data class User(val firstName: String?, val lastName: String, val age: Int, val hasSudo: Boolean?)

    @Test
    fun `it should preserve object columns across all core verbs`() {
        //TODO
    }


    @Test
    fun `it should allow to join on object columns`() {
        //TODO
    }


    @Test
    fun `it should convert data-classes to dataframes`() {
        data class Person(val name: String, val age: Int, val weight: Double)

        val users = listOf(
            Person("Anne", 23, 55.4),
            Person("Tina", 40, 60.4)
        )

        val df = users.asDataFrame()

        df.names shouldBe listOf("age", "name", "weight")
        df["age"][0] shouldBe 23
    }


    @Test
    fun `it should allow to map objects to df and back`() {
        val objPersons = users.rowsAs<User>()
        objPersons.toList().size shouldBe users.nrow


        // and back to df
        val df = objPersons.asDataFrame()
        df.nrow shouldBe 4
    }

    @Test
    fun `it should map rows to objects with custom mapping scheme`() {
        val objPersons = users
            .rename("firstName" to "Vorname")
            .rowsAs<User>(mapping = mapOf("Vorname" to "firstName"))

        objPersons.toList().size shouldBe users.nrow
    }

    @Test
    fun `it should provide the correct schema for object columns`() {
        val salaries = dataFrameOf("user", "salary")(User("Anna", "Doe", 23, null), 23.3)

        captureOutput {
            salaries.printDataClassSchema("salaries")
        }.first shouldBe """
        data class Salaries(val user: Any, val salary: Double)
        val records = salaries.rows.map { row -> Salaries( row["user"] as Any,  row["salary"] as Double) }
        """.trimIndent()

        data class Salaries(val user: Any, val salary: Double)

        val records = salaries.rows.map { row -> Salaries(row["user"] as Any, row["salary"] as Double) }
        records.size shouldBe 1
    }

    /** prevent regressions from "Provide more elegant object bindings #22"*/
    @Test
    fun `it should print nullable data class schemes`() {
        val stdout = captureOutput { users.printDataClassSchema("user") }.first
        stdout shouldBe """
            data class User(val firstName: String?, val lastName: String, val age: Int, val hasSudo: Boolean?)
            val records = user.rows.map { row -> User( row["firstName"] as String?,  row["lastName"] as String,  row["age"] as Int,  row["hasSudo"] as Boolean?) }
        """.trimIndent()
    }


}


internal fun captureOutput(expr: () -> Any): Pair<String, String> {
    val origOut = System.out
    val origErr = System.err
    // https://stackoverflow.com/questions/216894/get-an-outputstream-into-a-string

    val baosOut = ByteArrayOutputStream()
    val baosErr = ByteArrayOutputStream()

    System.setOut(PrintStream(baosOut));
    System.setErr(PrintStream(baosErr));


    // run the expression
    expr()

    val stdout = String(baosOut.toByteArray()).trim()
    val stderr = String(baosErr.toByteArray()).trim()

    System.setOut(origOut)
    System.setErr(origErr)

    return stdout to stderr
}

package github.leavesczy.wifip2p

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Catches a crash that compiles fine and only shows up on the phone: a string with format
 * placeholders that is called with the wrong number of arguments throws
 * MissingFormatArgumentException at run time. Every getString(...) call in the app is checked here
 * against the placeholders of its string resource.
 */
class ResourceFormatTest {

    private val moduleDir: File = listOf(File("."), File("app"), File("../app"))
        .first { File(it, "src/main/res/values/strings.xml").isFile }

    private fun strings(): Map<String, String> {
        val text = File(moduleDir, "src/main/res/values/strings.xml").readText()
        return Regex("""<string name="(\w+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** How many arguments a string needs: the highest explicit index, or the number of plain specifiers. */
    private fun requiredArgs(value: String): Int {
        val text = value.replace("%%", "")
        val indexed = Regex("""%(\d+)\$[sdf]""").findAll(text).map { it.groupValues[1].toInt() }.toList()
        if (indexed.isNotEmpty()) {
            return indexed.fold(0) { highest, n -> maxOf(highest, n) }
        }
        return Regex("""%[sdf]""").findAll(text).count()
    }

    /** The top-level arguments of the call whose opening parenthesis is at [open]. */
    private fun callArguments(code: String, open: Int): List<String> {
        val args = ArrayList<String>()
        var depth = 0
        var argStart = open + 1
        var inString = false
        var i = open
        while (i < code.length) {
            val c = code[i]
            if (inString) {
                if (c == '\\') {
                    i++
                } else if (c == '"') {
                    inString = false
                }
            } else if (c == '"') {
                inString = true
            } else if (c == '(' || c == '[' || c == '{') {
                depth++
            } else if (c == ')' || c == ']' || c == '}') {
                depth--
                if (depth == 0) {
                    args.add(code.substring(argStart, i).trim())
                    return args.filter { it.isNotEmpty() }
                }
            } else if (c == ',' && depth == 1) {
                args.add(code.substring(argStart, i).trim())
                argStart = i + 1
            }
            i++
        }
        return emptyList()
    }

    @Test
    fun everyFormattedStringGetsTheArgumentsItNeeds() {
        val strings = strings()
        val sources = File(moduleDir, "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        val call = Regex("""(getString|setText|setContentText|setTitle|setMessage)\(""")
        val problems = ArrayList<String>()
        for (file in sources) {
            val code = file.readText()
            for (match in call.findAll(code)) {
                val name = match.groupValues[1]
                val args = callArguments(code, match.range.last)
                if (args.isEmpty()) {
                    continue
                }
                val ids = Regex("""R\.string\.(\w+)""").findAll(args[0]).map { it.groupValues[1] }.toList()
                for (id in ids) {
                    val value = strings[id] ?: continue
                    val needed = requiredArgs(value)
                    val given = args.size - 1
                    if (name == "getString" && needed != given) {
                        problems.add("${file.name}: R.string.$id needs $needed argument(s) but gets $given")
                    }
                    if (name != "getString" && needed > 0) {
                        problems.add("${file.name}: R.string.$id has placeholders but $name() passes none")
                    }
                }
            }
        }
        assertTrue("Wrong format arguments:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}

package app.gains.ui.licenses

/**
 * A work in Gains that isn't a Maven dependency, so the AboutLibraries list can't know about it.
 * [notice] is what its license asks every copy to carry, in full; null when it asks for nothing.
 */
internal data class ThirdPartyWork(
    val name: String,
    val author: String,
    val license: String,
    val url: String,
    val usedFor: String,
    val notice: String?,
)

/**
 * The works written down by hand in NOTICE.md's exceptions that ship in the app. LicensesTest checks
 * each one against NOTICE.md, so the two can't drift apart. Their descriptions stay in English, like
 * the license texts.
 */
internal object ThirdPartyWorks {
    val bodyDrawing = ThirdPartyWork(
        name = "react-native-body-highlighter",
        author = "ELABBASSI Hicham",
        license = "MIT",
        url = "https://github.com/HichamELBSI/react-native-body-highlighter",
        usedFor = "The body outline behind the muscle map (BodyMapPaths.kt).",
        notice = """
            MIT License

            Copyright (c) 2022 ELABBASSI Hicham

            Permission is hereby granted, free of charge, to any person obtaining a copy
            of this software and associated documentation files (the "Software"), to deal
            in the Software without restriction, including without limitation the rights
            to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
            copies of the Software, and to permit persons to whom the Software is
            furnished to do so, subject to the following conditions:

            The above copyright notice and this permission notice shall be included in all
            copies or substantial portions of the Software.

            THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
            IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
            FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
            AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
            LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
            OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
            SOFTWARE.
        """.trimIndent(),
    )

    /** CC BY-SA 4.0: every copy credits Everkinetic, links the license, and says the drawings were changed. */
    val exerciseDrawings = ThirdPartyWork(
        name = "Everkinetic",
        author = "Everkinetic (everkinetic.com, Greg Priday)",
        license = "CC BY-SA 4.0",
        url = "https://github.com/everkinetic/data",
        usedFor = "The start and end drawings under \"How to do it\" (composeResources/files/exercises), adapted by tools/exercise_demos.py.",
        notice = """
            Exercise drawings by Everkinetic (https://github.com/everkinetic/data), from everkinetic.com
            by Greg Priday, licensed under the Creative Commons Attribution-ShareAlike 4.0 International
            License (CC BY-SA 4.0, https://creativecommons.org/licenses/by-sa/4.0/). Gains adapted them:
            each pair is cropped together, scaled, and its white made transparent so the app can draw the
            lines in its own colours. The adapted drawings are licensed under CC BY-SA 4.0 too. They are
            provided as is, without warranties.
        """.trimIndent(),
    )

    /** Public domain, so nothing is owed; credited because some of the catalogue's names and muscles come from it. */
    val exerciseData = ThirdPartyWork(
        name = "free-exercise-db",
        author = "yuhonas and contributors",
        license = "Unlicense",
        url = "https://github.com/yuhonas/free-exercise-db",
        usedFor = "Some of the built-in exercises' names and muscle groups (the JSON data only, not its photos).",
        notice = null,
    )

    val all = listOf(bodyDrawing, exerciseDrawings, exerciseData)
}

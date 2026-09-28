package app.gains.root

import app.gains.domain.ProgramDayRef
import app.gains.domain.ProgramLink
import app.gains.domain.ProgramState
import app.gains.program.Rotation
import app.gains.ui.i18n.Texts
import app.gains.ui.i18n.resolvedName

/** The active program's next day, offered first in the "+" menu under [dayName]. */
internal data class UpNext(val ref: ProgramDayRef, val dayName: String)

/**
 * The day [Rotation] says comes next in the active program, named in [texts]' language, or null
 * with no active program or one with no days. A plain function rather than a combine in the root,
 * so which day is offered, and in which words, is tested on its own.
 */
internal suspend fun findUpNext(state: ProgramState, links: List<ProgramLink>, texts: Texts): UpNext? {
    val program = state.active ?: return null
    val day = Rotation.nextDay(program, links) ?: return null
    return UpNext(ProgramDayRef(program.id, day.id), day.resolvedName(texts))
}

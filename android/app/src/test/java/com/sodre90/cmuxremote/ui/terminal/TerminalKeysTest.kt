package com.sodre90.cmuxremote.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalKeysTest {
    private val map = TerminalKeys.toMap()

    @Test fun arrowUpIsCsiA() = assertEquals("\u001b[A", map["↑"])
    @Test fun pageUpIsCsi5Tilde() = assertEquals("\u001b[5~", map["PgUp"])
    @Test fun ctrlCIsEtx() = assertEquals("\u0003", map["^C"])
    @Test fun homeIsCsiH() = assertEquals("\u001b[H", map["Home"])
    @Test fun endIsCsiF() = assertEquals("\u001b[F", map["End"])
}

package com.zfdang.chess.jieqi

import org.junit.Test
import java.util.Random

/** 精确重放到用户问题局面：车翻出在e4、黑炮在e7、轮红走。输出可写入的存档串。 */
class JieqiRebuildProblemTest {
    @Test
    fun rebuild() {
        val s = JieqiGameState.newGame(Random(42))
        val a = s.assignment!!
        // 按对局记录设置关键暗子的真实身份
        a[7][7] = 'A'   // h2 红仕
        a[3][0] = 'a'   // a6 黑仕
        a[7][1] = 'P'   // b2 红兵
        a[3][8] = 'n'   // i6 黑马
        a[6][8] = 'P'   // i3 红兵
        a[3][6] = 'b'   // g6 黑相
        a[6][4] = 'R'   // e3 红兵 -> 翻出车
        a[0][2] = 'c'   // c9 黑炮

        // 重放到 c9e7c（车已翻出、黑炮就位、轮红走）
        val tokens = "h2h9A a6a5a b2b9P i6i5n i3i4P g6g5b e3e4R c9e7c".split(" ")
        for (tok in tokens) {
            val fr="abcdefghi".indexOf(tok[0]); val fc=tok[1]-'0'
            val tr="abcdefghi".indexOf(tok[2]); val tc=tok[3]-'0'
            val gr=9-fc; val gtr=9-tc
            val rev=if(tok.length>=5)tok[4] else '.'
            val vic=if(tok.length>=6)tok[5] else '.'
            val err=s.applyMove(gr,fr,gtr,tr,rev,vic,trustEngine=true)
            if(err!=null) println("ERR $tok: $err")
        }
        println("=== PROBLEM GRID ===")
        for (r in 0 until 10) println("row$r: ${String(s.grid[r])}")
        println("redToMove=${s.redToMove}")
        println("红车 e4=grid[5][4]='${s.grid[5][4]}'  炮e7=grid[2][4]='${s.grid[2][4]}'  暗子e6=grid[3][4]='${s.grid[3][4]}'")
        val carLegal = JieqiRules.isLegal(s.grid, true, 5, 4, 3, 4)
        println("车e4进2吃e6暗子 legal=$carLegal")
        println("=== SAVE ===")
        println("1"); println("true"); println("false")
        println(s.serialize())
    }
}

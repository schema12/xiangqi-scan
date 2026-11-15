package com.zfdang.chess.openbook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.zfdang.chess.gamelogic.Board;
import com.zfdang.chess.gamelogic.Move;
import com.zfdang.chess.gamelogic.Zobrist;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 激进专用开局库数据完整性验证(真实数据,非编造):
 * 资产存在、行数、结构、起始局面命中、vmove 可解码、obj_eval 覆盖率、
 * 统计一致性、候选着法合法性。
 */
public class AggressiveBookIntegrityTest {

    private static final String DB_RELATIVE =
            "src/main/assets/databases/aggressive_book_v1.obk";
    private static Connection conn;

    @BeforeClass
    public static void open() throws Exception {
        File db = new File(DB_RELATIVE);
        assumeTrue("开局库资产未生成(跳过数据完整性测试)", db.isFile());
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
    }

    @Test public void rowCountIsMeaningful() throws Exception {
        int n = scalar("select count(*) from bhobk");
        assertTrue("行数应 ≥ 5000,实际 " + n, n >= 5000);
    }

    @Test public void enrichedColumnsExistAndPopulated() throws Exception {
        int tagged = scalar("select count(*) from bhobk where tag is not null and class is not null");
        assertTrue("质量列应已填充", tagged > 0);
        int evald = scalar("select count(*) from bhobk where obj_eval is not null");
        int total = scalar("select count(*) from bhobk");
        assertTrue("Pikafish obj_eval 覆盖率应 ≥ 90%(实际 " + evald + "/" + total + ")",
                evald * 100 >= total * 90);
        Set<String> classes = new HashSet<>();
        try (ResultSet rs = conn.createStatement().executeQuery("select distinct class from bhobk")) {
            while (rs.next()) classes.add(rs.getString(1));
        }
        assertTrue("class 取值受限", classes.stream().allMatch(
                c -> "normal".equals(c) || "sound_attack".equals(c) || "offbeat_loss".equals(c)));
    }

    @Test public void statsAreConsistent() throws Exception {
        int bad = scalar("select count(*) from bhobk where vscore <> 3*vwin + vdraw");
        assertEquals("vscore 必须等于 3*胜 + 和", 0, bad);
        int negative = scalar("select count(*) from bhobk where vwin < 0 or vdraw < 0 or vlost < 0");
        assertEquals(0, negative);
    }

    @Test public void startPositionHasBookMoves() throws Exception {
        Board board = new Board();
        long vkey = board.getZobrist(true);
        List<String> moves = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "select vmove from bhobk where vvalid = 1 and vkey = ?")) {
            ps.setLong(1, vkey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) moves.add(Zobrist.getMoveFromVmove(rs.getInt(1)));
            }
        }
        assertTrue("起始局面应有开局候选(实际 " + moves.size() + ")", moves.size() >= 2);
        // 起始局面的候选必须是合法着法子集
        Set<String> legal = new HashSet<>();
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 9; x++) {
                int p = board.getPieceByPosition(x, y);
                if (!com.zfdang.chess.gamelogic.Piece.isValid(p)
                        || !com.zfdang.chess.gamelogic.Piece.isRed(p)) continue;
                for (com.zfdang.chess.gamelogic.Position t
                        : com.zfdang.chess.gamelogic.Rule.PossibleToPositions(p, x, y, board)) {
                    legal.add(new Move(new com.zfdang.chess.gamelogic.Position(x, y), t, board).getUCCIString());
                }
            }
        }
        for (String m : moves) {
            assertTrue("开局候选必须是合法着法: " + m, legal.contains(m));
        }
    }

    @Test public void everyMoveDecodesToFourCharUcci() throws Exception {
        int bad = 0;
        try (ResultSet rs = conn.createStatement().executeQuery("select vmove from bhobk")) {
            while (rs.next()) {
                String ucci = Zobrist.getMoveFromVmove(rs.getInt(1));
                if (ucci == null || ucci.length() != 4) bad++;
            }
        }
        assertEquals("所有 vmove 必须可解码为 4 字符 UCCI", 0, bad);
    }

    @Test public void soundAttackRowsAreObjectivelySound() throws Exception {
        int bad = scalar("select count(*) from bhobk where class = 'sound_attack'"
                + " and obj_eval is not null and obj_eval < -50");
        assertEquals("sound_attack 类别的客观分不得低于 -50cp", 0, bad);
        int offbeat = scalar("select count(*) from bhobk where class = 'offbeat_loss'"
                + " and obj_eval is not null and obj_eval > -150");
        assertEquals("offbeat_loss 类别的客观分必须 < -150cp", 0, offbeat);
    }

    private static int scalar(String sql) throws Exception {
        try (ResultSet rs = conn.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}

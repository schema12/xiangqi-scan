package booktools;

import com.zfdang.chess.gamelogic.Board;
import com.zfdang.chess.gamelogic.Move;
import com.zfdang.chess.gamelogic.Piece;
import com.zfdang.chess.gamelogic.Position;
import com.zfdang.chess.gamelogic.Rule;
import com.zfdang.chess.manuals.XQFManual;
import com.zfdang.chess.manuals.XQFParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 构建期工具(仅测试类路径,绝不进入 APK):把仓库自带 XQF 棋谱转换为
 * 激进专用开局库 SQLite 资产。
 *
 * 数据来源 = 仓库自带 XQF(真实对局/教材谱,由用户对版权负责);
 * 转换 = 去重 → 标准开局校验 → 主变回放(逐着合法性校验)→ Zobrist vkey 聚合
 *       → 出现频次筛选 → Pikafish 静态 eval 打 obj_eval → 规则分类。
 * 不编造任何着法或统计:每个条目都能追溯到真实棋谱文件与真实结果标签。
 *
 * 分类规则(规则性,非人工标注):
 *   obj_eval < -150cp               -> class=offbeat_loss(单纯偏门亏损)
 *   有将军 / 吃子值>=马炮 且 >=-50cp -> tag=aggressive, class=sound_attack
 *   其余                             -> tag=normal, class=normal
 */
public final class BookBuilder {

    static final int MAX_PLY = 14;          // 开局阶段上限(双方共 7 步)
    static final int MIN_GAME_COUNT = 2;    // 过渡出现次数下限(过滤孤例)
    static final int MAX_ROWS = 8000;       // 最终条目上限(按频次取前 N)
    static final int OBJ_EVAL_OFFBEAT = -150;
    static final int OBJ_EVAL_SOUND = -50;

    // ---- vmove 编码镜像(与 Zobrist.coordMap 一致,不能改数值) ----
    static final int[] C90 = {
            0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x3b,
            0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4a, 0x4b,
            0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x5b,
            0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x6b,
            0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x7b,
            0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a, 0x8b,
            0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0x9b,
            0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xab,
            0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xbb,
            0xc3, 0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xcb
    };
    static final String[] SAN90 = {
            "a9", "b9", "c9", "d9", "e9", "f9", "g9", "h9", "i9",
            "a8", "b8", "c8", "d8", "e8", "f8", "g8", "h8", "i8",
            "a7", "b7", "c7", "d7", "e7", "f7", "g7", "h7", "i7",
            "a6", "b6", "c6", "d6", "e6", "f6", "g6", "h6", "i6",
            "a5", "b5", "c5", "d5", "e5", "f5", "g5", "h5", "i5",
            "a4", "b4", "c4", "d4", "e4", "f4", "g4", "h4", "i4",
            "a3", "b3", "c3", "d3", "e3", "f3", "g3", "h3", "i3",
            "a2", "b2", "c2", "d2", "e2", "f2", "g2", "h2", "i2",
            "a1", "b1", "c1", "d1", "e1", "f1", "g1", "h1", "i1",
            "a0", "b0", "c0", "d0", "e0", "f0", "g0", "h0", "i0"
    };

    static String standardStartBoard =
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR";

    /** 一条聚合后的开局过渡。 */
    static final class Row {
        long vkey;
        int vmove;
        String ucci;
        String fenBefore;
        String fenAfter;
        int win, draw, lose;   // 轮走方视角
        int games;
        int capValue;          // 该着吃到的子力(0 = 不吃)
        boolean givesCheck;    // 该着是否将军
        Integer objEval;       // Pikafish eval(red POV, cp);null = 未评估
    }

    public static void main(String[] args) throws Exception {
        String srcDir = args.length > 0 ? args[0] : "app/src/main/assets/XQF";
        String pikafishExe = args.length > 1 ? args[1] : "";
        String nnuePath = args.length > 2 ? args[2] : "";
        String outDb = args.length > 3 ? args[3] : "app/src/main/assets/databases/aggressive_book_v1.obk";

        long t0 = System.currentTimeMillis();
        System.out.println("[book] source dir = " + srcDir);

        List<Path> files = collectXqf(srcDir);
        System.out.println("[book] xqf files = " + files.size());

        Map<Long, List<Row>> byKey = new HashMap<>();
        Set<String> seenMd5 = new HashSet<>();
        int parsed = 0, standard = 0, replayed = 0, skipped = 0;

        for (Path p : files) {
            byte[] data = Files.readAllBytes(p);
            String md5 = md5(data);
            if (!seenMd5.add(md5)) { skipped++; continue; }
            XQFManual manual;
            try {
                manual = XQFParser.parse(data);
            } catch (Throwable t) {
                skipped++; continue;
            }
            if (manual == null || manual.board == null) { skipped++; continue; }
            parsed++;
            if (!manual.board.toFENString().startsWith(standardStartBoard)) continue; // 非标准开局(残局教材等)
            standard++;
            String result = manual.getResult() == null ? "" : manual.getResult();
            Integer redResult; // 1 红胜, -1 黑胜, 0 平局, null 未知
            if (result.contains("红胜")) redResult = 1;
            else if (result.contains("黑胜")) redResult = -1;
            else if (result.contains("平")) redResult = 0;
            else redResult = null;
            if (redResult == null) continue; // 无结果标签的游戏不参与统计

            // 主变回放(逐着合法性校验)
            Board cur = new Board(manual.board);
            XQFManual.MoveNode node = manual.getHeadMove();
            boolean broken = false;
            int ply = 0;
            while (ply < MAX_PLY) {
                if (node.nextMoves == null || node.nextMoves.isEmpty()) break;
                XQFManual.MoveNode child = node.nextMoves.get(0); // 主变 = 第一分支
                if (child.move == null || child.move.fromPosition == null) { broken = true; break; }
                final Board before = cur;
                Move m = new Move(child.move.fromPosition, child.move.toPosition, before);
                if (!Rule.isValidMove(m, before)) { broken = true; break; }
                String ucci = m.getUCCIString();
                long vkey = before.getZobrist(before.bRedGo);
                boolean moverRed = before.bRedGo;
                boolean moverWin = (moverRed && redResult == 1) || (!moverRed && redResult == -1);
                boolean moverLose = (moverRed && redResult == -1) || (!moverRed && redResult == 1);
                int capValue = 0;
                int victim = before.getPieceByPosition(m.toPosition.x, m.toPosition.y);
                if (Piece.isValid(victim)) capValue = StrategyValues.materialValue(victim);
                final Board after = new Board(before);
                after.doMove(m);
                boolean check = givesCheck(after, moverRed);

                List<Row> list = byKey.computeIfAbsent(vkey, k -> new ArrayList<>());
                Row row = findOrCreate(list, vkey, ucci);
                if (row == null) {
                    row = new Row();
                    row.vkey = vkey;
                    row.ucci = ucci;
                    list.add(row);
                }
                row.vmove = encodeVmove(ucci);
                row.fenBefore = before.toFENString();
                row.fenAfter = after.toFENString();
                row.capValue = Math.max(row.capValue, capValue);
                row.givesCheck = row.givesCheck || check;
                if (moverWin) row.win++;
                else if (moverLose) row.lose++;
                else row.draw++;
                row.games++;

                cur = after;
                node = child;
                ply++;
            }
            if (!broken && ply > 0) replayed++;
        }
        System.out.println("[book] parsed=" + parsed + " standardStart=" + standard
                + " replayed=" + replayed + " skipped=" + skipped
                + " transitions=" + byKey.size());

        // 频次筛选 + 每局面限 8 着
        List<Row> selected = new ArrayList<>();
        for (List<Row> list : byKey.values()) {
            list.sort(Comparator.comparingInt((Row r) -> -(r.win + r.draw + r.lose)));
            int taken = 0;
            for (Row r : list) {
                if (taken >= 8) break;
                if (r.games < MIN_GAME_COUNT) break;
                selected.add(r);
                taken++;
            }
        }
        selected.sort(Comparator.comparingInt((Row r) -> -r.games));
        if (selected.size() > MAX_ROWS) selected.subList(MAX_ROWS, selected.size()).clear();
        System.out.println("[book] selected rows = " + selected.size());

        // Pikafish 静态 eval(red POV)
        if (!pikafishExe.isEmpty()) {
            evalWithPikafish(selected, pikafishExe, nnuePath);
        } else {
            System.out.println("[book] no pikafish exe given -> obj_eval left NULL (unverified)");
        }

        // 写 SQLite
        File out = new File(outDb);
        out.getParentFile().mkdirs();
        if (out.exists()) out.delete();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + out.getAbsolutePath())) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE bhobk(" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "vkey INTEGER, vmove INTEGER, vscore INTEGER, vwin INTEGER, vdraw INTEGER," +
                        "vlost INTEGER, vvalid INTEGER, vmemo TEXT, vindex INTEGER," +
                        "tag TEXT, obj_eval INTEGER, refuted INTEGER DEFAULT 0, class TEXT," +
                        "source TEXT, license TEXT, risk_min INTEGER DEFAULT 0, risk_max INTEGER DEFAULT 100)");
                st.execute("CREATE INDEX idx_bhobk_vkey ON bhobk(vkey, vvalid)");
                st.execute("CREATE INDEX idx_bhobk_tag ON bhobk(tag, class, refuted)");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "insert into bhobk(vkey,vmove,vscore,vwin,vdraw,vlost,vvalid,vmemo,vindex," +
                            "tag,obj_eval,refuted,class,source,license,risk_min,risk_max) " +
                            "values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                int n = 0;
                for (Row r : selected) {
                    ps.setLong(1, r.vkey);
                    ps.setInt(2, r.vmove);
                    ps.setInt(3, 3 * r.win + r.draw);
                    ps.setInt(4, r.win);
                    ps.setInt(5, r.draw);
                    ps.setInt(6, r.lose);
                    ps.setInt(7, 1);
                    ps.setString(8, r.ucci);
                    ps.setInt(9, 0);
                    boolean sound = r.objEval == null || r.objEval >= OBJ_EVAL_SOUND;
                    String cls;
                    String tag;
                    if (r.objEval != null && r.objEval < OBJ_EVAL_OFFBEAT) {
                        cls = "offbeat_loss";
                        tag = "normal";
                    } else if (sound && (r.givesCheck || r.capValue >= 450)) {
                        cls = "sound_attack";
                        tag = "aggressive";
                    } else {
                        cls = "normal";
                        tag = "normal";
                    }
                    ps.setString(10, tag);
                    if (r.objEval != null) ps.setInt(11, r.objEval); else ps.setNull(11, java.sql.Types.INTEGER);
                    ps.setInt(12, 0);
                    ps.setString(13, cls);
                    ps.setString(14, "repo-xqf");
                    ps.setString(15, "user-responsibility");
                    ps.setInt(16, 0);
                    ps.setInt(17, 100);
                    ps.addBatch();
                    n++;
                }
                ps.executeBatch();
                System.out.println("[book] written rows = " + n);
            }
        }
        System.out.println("[book] db = " + out.getAbsolutePath()
                + " (" + out.length() + " bytes, " + (System.currentTimeMillis() - t0) + "ms)");
    }

    private static Row findOrCreate(List<Row> list, long vkey, String ucci) {
        for (Row r : list) {
            if (r.vkey == vkey && r.ucci.equals(ucci)) return r;
        }
        return null;
    }

    private static List<Path> collectXqf(String srcDir) throws IOException {
        List<Path> out = new ArrayList<>();
        Files.walk(Path.of(srcDir)).forEach(p -> {
            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (n.endsWith(".xqf")) out.add(p);
        });
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    private static String md5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(data)) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static int byteOf(String sq) {
        for (int i = 0; i < 90; i++) {
            if (SAN90[i].equals(sq)) return C90[i];
        }
        throw new IllegalArgumentException("bad square: " + sq);
    }

    static int encodeVmove(String ucci) {
        String from = ucci.substring(0, 2);
        String to = ucci.substring(2, 4);
        return (byteOf(to) << 8) | byteOf(from);
    }

    private static boolean givesCheck(Board after, boolean moverRed) {
        int enemyKing = moverRed ? Piece.BJIANG : Piece.WSHUAI;
        Position kp = Rule.findJiangShuaiPos(enemyKing, after);
        if (kp == null) return false;
        return Rule.isJiangShuaiInDanger(enemyKing, kp, after);
    }

    /** 用 Pikafish 的 eval 命令给每个 after 局面打静态分(red POV, cp)。 */
    private static void evalWithPikafish(List<Row> rows, String exe, String nnue) {
        try {
            ProcessBuilder pb = new ProcessBuilder(exe);
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            BufferedWriter in = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream()));
            BufferedReader out = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            send(in, "setoption name Threads value 1");
            if (!nnue.isEmpty()) send(in, "setoption name EvalFile value " + nnue);
            send(in, "setoption name UCI_ShowWDL value false");
            send(in, "uci");
            String line;
            while ((line = out.readLine()) != null) {
                if (line.equals("uciok")) break;
            }
            send(in, "isready");
            while ((line = out.readLine()) != null) {
                if (line.equals("readyok")) break;
            }
            int done = 0, failed = 0;
            for (Row r : rows) {
                send(in, "position fen " + r.fenAfter);
                send(in, "eval");
                String value = null;
                boolean inCheck = false;
                while ((line = out.readLine()) != null) {
                    if (line.startsWith("Final evaluation")) {
                        if (line.contains("none (in check)")) { inCheck = true; break; }
                        String[] tokens = line.trim().split("\\s+");
                        // "Final evaluation      +0.35 (white side) ..."
                        for (String t : tokens) {
                            if (t.matches("[+-]\\d+\\.\\d+.*")) {
                                value = t;
                                break;
                            }
                        }
                        break;
                    }
                    if (line.startsWith("bestmove")) break; // safety
                }
                if (inCheck || value == null) {
                    r.objEval = null;
                    failed++;
                } else {
                    try {
                        r.objEval = (int) Math.round(Double.parseDouble(value) * 100);
                    } catch (NumberFormatException nfe) {
                        r.objEval = null;
                        failed++;
                    }
                }
                done++;
                if (done % 500 == 0) System.out.println("[book] eval " + done + "/" + rows.size());
            }
            send(in, "quit");
            proc.waitFor();
            System.out.println("[book] eval done: ok=" + (done - failed) + " failed=" + failed);
        } catch (Exception e) {
            System.out.println("[book] eval pipeline failed: " + e);
        }
    }

    private static void send(BufferedWriter in, String cmd) throws IOException {
        in.write(cmd);
        in.newLine();
        in.flush();
    }

    private BookBuilder() {
    }
}

/** 子力表(仅构建期分类用,与 StrategyParams 保持同一量纲)。 */
final class StrategyValues {
    static int materialValue(int pieceType) {
        switch (pieceType) {
            case 5: case 12: return 900;
            case 4: case 11: return 450;
            case 6: case 13: return 450;
            case 2: case 9: return 120;
            case 3: case 10: return 120;
            case 7: case 14: return 100;
            default: return 0;
        }
    }

    private StrategyValues() {
    }
}

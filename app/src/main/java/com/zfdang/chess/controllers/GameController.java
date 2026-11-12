package com.zfdang.chess.controllers;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.zfdang.chess.ChessApp;
import com.zfdang.chess.Settings;
import com.zfdang.chess.gamelogic.Board;
import com.zfdang.chess.gamelogic.Game;
import com.zfdang.chess.gamelogic.GameStatus;
import com.zfdang.chess.gamelogic.Move;
import com.zfdang.chess.gamelogic.Piece;
import com.zfdang.chess.gamelogic.Position;
import com.zfdang.chess.gamelogic.PvInfo;
import com.zfdang.chess.gamelogic.Rule;
import com.zfdang.chess.openbook.BHOpenBook;
import com.zfdang.chess.openbook.BookData;
import com.zfdang.chess.openbook.OpenBook;

import org.jetbrains.annotations.NotNull;
import org.petero.droidfish.player.ComputerPlayer;
import org.petero.droidfish.player.EngineListener;
import org.petero.droidfish.player.SearchListener;
import org.petero.droidfish.player.SearchRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GameController implements EngineListener, SearchListener {
    public ComputerPlayer player = null;
    private String engineName = "pikafish";
    public String engineInfo; // returned by engine

    // 如果开启了电脑的着法随机选项，那么前N步会随机选择一个着法。这个数值不易过大，否则电脑棋力下降太多
    private final int random_before_max_rounds = 12;

    public Game game = null;
    private int searchId;
    private long searchStartTime;
    private long searchEndTime;

    public boolean isComputerPlaying = true;

    /** 执棋模式：0=人执红·电脑执黑 1=红黑双人 2=人执黑·电脑执红 */
    public int playerMode = 0;
    /** 人执红？(false=人执黑，电脑执红) */
    public boolean humanPlaysRed = true;

    /** 现在轮到引擎出子？= 电脑开启 且 轮走方不是人执的一侧 */
    public boolean isEngineTurnNow() {
        boolean redToMove = game != null && game.currentBoard != null && game.currentBoard.bRedGo;
        return isComputerPlaying && (redToMove != humanPlaysRed);
    }

    /** 现在轮到人出子？(双人模式恒为真) */
    public boolean isHumanTurnNow() {
        return !isEngineTurnNow();
    }
    public boolean isAutoPlay = true;

    public boolean isShowTrends = false;

    private ControllerListener gui = null;
    protected volatile List<PvInfo> multiPVs = Collections.emptyList();
    protected List<PvInfo> suggestedPVs = Collections.emptyList();

    BHOpenBook bhBook = null;

    private static final String GAME_OVER_HINT = "对局已结束，可悔棋或开新局";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean closed = false;
    private final Object pvLock = new Object();

    public void close() {
        synchronized (pvLock) {
            closed = true;
        }
        mainHandler.removeCallbacksAndMessages(null);
        if (player != null) player.close();
    }

    public volatile ControllerState state;
    public volatile ControllerState preEvalState;
    // 正在被评估的局面，评估结果写回到它上面
    private volatile Board evalBoard = null;

    public Settings settings = null;

    public void toggleShowTrends() {
        isShowTrends = !isShowTrends;
    }

    // create enum for controller state
    enum ControllerState {
        IDLE,
        WAITING_FOR_USER, // 红方出子
        WAITING_FOR_USER_MULTIPV, // 红方寻求帮助，等待多PV结果
        WAITING_FOR_ENGINE, // 黑方出子
        WAITING_FOR_ENGINE_BESTMV, // 黑方寻找最佳着法
        WAITING_FOR_ENGINE_MULTIPV, // 黑方变着，等待多PV结果
        WAITING_FOR_EVAL, // 等待eval的结果
        MANUAL_MODE, // 打谱模式
    }

    public boolean isNonGameMode() {
        return state == ControllerState.MANUAL_MODE;
    }
    // Allows controller callback tests without starting a native engine or opening databases.
    GameController(ControllerListener listener, ComputerPlayer computerPlayer) {
        gui = listener;
        player = computerPlayer;
    }

    public GameController(ControllerListener cListener) {
        this(cListener, null);

        isComputerPlaying = true;
        isAutoPlay = true;
        searchId = 0;

        bhBook = new BHOpenBook(ChessApp.getContext());

        settings = new Settings(ChessApp.getContext());

        // Initialize computer player
        if (player == null) {
            player = new ComputerPlayer(this, this, this);
        }
        player.queueStartEngine(searchId++, engineName);
        startNewGame();
    }

    public synchronized void startNewGame() {
        Log.d("GameController", "New game");
        setSatate(settings.getRed_go_first());
        game = new Game(settings.getRed_go_first());
        player.stopSearch();
        player.uciNewGame();
        maybeEngineForward();
    }

    public synchronized void startFENGame(String fen) {
        Log.d("GameController", "New FEN game" + fen);
        Game tempGame = new Game(true);
        boolean result = tempGame.currentBoard.restoreFromFEN(fen);
        if (!result) {
            Log.e("GameController", "Failed to restore from FEN: " + fen);
            gui.onGameEvent(GameStatus.ILLEGAL, "无效的FEN串");
            return;
        }

        game = tempGame;
        setSatate(game.currentBoard.bRedGo);
        game.clearHistory();
        game.startPos = null;
        game.endPos = null;
        gui.onGameEvent(GameStatus.UPDATEUI, "从FEN开局");
        player.stopSearch();
        player.uciNewGame();
        // 若开局即轮到电脑（含人执黑等模式），自动出手
        maybeEngineForward();
    }

    public synchronized void toggleComputer() {
        isComputerPlaying = !isComputerPlaying;
    }

    /** 执棋模式三态循环：人执红·电脑执黑 → 红黑双人 → 人执黑·电脑执红 */
    public synchronized int cyclePlayerMode() {
        playerMode = (playerMode + 1) % 3;
        isComputerPlaying = (playerMode != 1);
        humanPlaysRed = (playerMode != 2);
        // 中途切换：停掉进行中的搜索，并把状态复位为当前轮走方
        if (player != null) player.stopSearch();
        if (game != null && game.currentBoard != null) {
            setSatate(game.currentBoard.bRedGo);
        }
        return playerMode;
    }

    /** 轮到引擎时让它出手（自动走棋开启时） */
    public void maybeEngineForward() {
        GcLog.d("maybeEngineForward: auto=" + isAutoPlay + " engineTurn=" + isEngineTurnNow());
        if (isAutoPlay && isEngineTurnNow()) {
            mainHandler.postDelayed(this::computerForward, 500);
        }
    }

    /** 执棋权刚移交引擎（切换执棋模式）时立即出手，不受自动走棋开关限制 */
    public void forceEngineForward() {
        GcLog.d("forceEngineForward: engineTurn=" + isEngineTurnNow() + " auto=" + isAutoPlay);
        if (isEngineTurnNow()) {
            mainHandler.postDelayed(this::computerForward, 500);
        }
    }

    public synchronized void toggleComputerAutoPlay() {
        isAutoPlay = !isAutoPlay;
    }

    /** 当前轮走颜色（以棋盘 bRedGo 为准，与执棋方解耦） */
    public boolean isRedTurn() {
        return game != null && game.currentBoard != null && game.currentBoard.bRedGo;
    }

    public boolean isBlackTurn() {
        return game != null && game.currentBoard != null && !game.currentBoard.bRedGo;
    }

    public synchronized void setSatate(boolean isRedTurn) {
        // WAITING_FOR_USER=轮到人出子，WAITING_FOR_ENGINE=轮到引擎出子；
        // 由 humanPlaysRed 推导，不再把红方硬编码为人
        if (isRedTurn == humanPlaysRed) {
            state = ControllerState.WAITING_FOR_USER;
        } else {
            state = ControllerState.WAITING_FOR_ENGINE;
        }
    }

    // this can be called by either GUI or computer
    public synchronized boolean stepBack() {
        // 只在WAINTING_FOR_USER或WAINTING_FOR_ENGINE状态下才能悔棋
        if (state != ControllerState.WAITING_FOR_USER && state != ControllerState.WAITING_FOR_ENGINE) {
            gui.onGameEvent(GameStatus.ILLEGAL, "搜索中，请稍候...");
            return false;
        }
        if (game.history.size() > 0) {
            Game.HistoryRecord record = game.undoMove();
            setSatate(record.isRedMove);
            gui.onGameEvent(GameStatus.MOVE, game.getLastMoveDesc());
            return true;
        } else {
            gui.onGameEvent(GameStatus.ILLEGAL, "无棋可悔");
            return false;
        }
    }

    // computer to play his turn
    public synchronized void computerForward() {
        GcLog.d("computerForward enter: closed=" + closed + " state=" + state
                + " bRedGo=" + (game != null && game.currentBoard != null ? game.currentBoard.bRedGo : null)
                + " engineTurn=" + isEngineTurnNow() + " auto=" + isAutoPlay);
        if (closed) return;
        if (game.isGameOver) {
            GcLog.d("computerForward: game over");
            gui.onGameEvent(GameStatus.ILLEGAL, GAME_OVER_HINT);
            return;
        }
        if (!isEngineTurnNow()) {
            // 轮到人出子（或对局结束等）
            GcLog.d("computerForward: not engine turn, skip");
            gui.onGameEvent(GameStatus.ILLEGAL, "该你出子");
            return;
        }
        if (state != ControllerState.WAITING_FOR_ENGINE) {
            // play only plays the black
            gui.onGameEvent(GameStatus.ILLEGAL, "搜索中，请稍候或闪电出着");
            return;
        }

        if(settings.getOpenbook()) {
            // 开局库查询挪到后台线程（120MB 大库无索引首次查询慢，不能阻塞主线程）
            // 查询命中：电脑直接走子；未命中/异常：回到主线程正常搜索
            final boolean redGo = isRedTurn();
            new Thread(() -> {
                List<BookData> bookData = null;
                try {
                    GcLog.d("openbook query start");
                    long vkey = game.currentBoard.getZobrist(redGo);
                    bookData = bhBook.query(vkey, redGo, OpenBook.SortRule.BEST_SCORE);
                } catch (Throwable t) {
                    GcLog.d("openbook query failed: " + t);
                }
                final List<BookData> hit = bookData;
                mainHandler.post(() -> {
                    if (hit != null && hit.size() > 0) {
                        int idx = 0;
                        if(game.currentBoard.rounds <= random_before_max_rounds && settings.getRandom_move()) {
                            idx = (int) (Math.random() * hit.size());
                        }
                        GcLog.d("openbook hit: " + hit.size());
                        computerMovePiece(hit.get(idx).getMove());
                    } else {
                        // 未命中开局库：正常发起引擎搜索
                        startEngineSearch();
                    }
                });
            }, "OpenbookQuery").start();
            return;
        }

        if (settings.getGo_infinite()) {
            gui.onGameEvent(GameStatus.UPDATEUI, "无限搜索着法中, 须闪电出着!");
        } else {
            gui.onGameEvent(GameStatus.UPDATEUI, "搜索着法中...");
        }
        startEngineSearch();
    }

    /** 发起引擎搜索（openbook 未命中时由后台线程回调主线程执行） */
    private void startEngineSearch() {
        if (game.isGameOver) {
            gui.onGameEvent(GameStatus.ILLEGAL, GAME_OVER_HINT);
            return;
        }
        if (settings.getGo_infinite()) {
            gui.onGameEvent(GameStatus.UPDATEUI, "无限搜索着法中, 须闪电出着!");
        } else {
            gui.onGameEvent(GameStatus.UPDATEUI, "搜索着法中...");
        }
        state = ControllerState.WAITING_FOR_ENGINE_BESTMV;

        // trigger searchrequest, engine will call notifySearchResult for bestmove
        queueSearch(settings.getRandom_move() ? 3 : 1);
    }

    public synchronized void computerAskForMultiPV() {
        if (state == ControllerState.WAITING_FOR_ENGINE_MULTIPV
                || state == ControllerState.WAITING_FOR_USER_MULTIPV) {
            gui.onGameEvent(GameStatus.ILLEGAL, "搜索变着中,请稍候或闪电出着");
            return;
        }
        if (state == ControllerState.WAITING_FOR_ENGINE_BESTMV) {
            gui.onGameEvent(GameStatus.ILLEGAL, "电脑正在出着,请稍候或闪电出着");
            return;
        }

        // 变着=撤销引擎上一步再让它重想：仅当现在轮到引擎出子时才有意义
        if (!isEngineTurnNow()) {
            boolean result = stepBack();
            if (!result) {
                gui.onGameEvent(GameStatus.ILLEGAL, "无着可变");
                return;
            }
        }

        // 最后检查目前的状态是否是等待引擎出着
        if (!isEngineTurnNow()) {
            Log.d("GameController", "Invalid state, computerAskForMultiPV should not be called now." + state);
            gui.onGameEvent(GameStatus.ILLEGAL, "轮到引擎出招时方可变着");
            return;
        }

        // 开始搜索中
        if (settings.getGo_infinite()) {
            gui.onGameEvent(GameStatus.UPDATEUI, "无限搜索变着中, 须闪电出着!");
        } else {
            gui.onGameEvent(GameStatus.UPDATEUI, "搜索变着中...");
        }
        // 引擎自己要求的变着搜索，结果由 notifySearchResult 按引擎分支处理
        state = ControllerState.WAITING_FOR_ENGINE_MULTIPV;
        multiPVs = Collections.emptyList();

        // trigger searchrequest, engine will call notifySearchResult for bestmove
        queueSearch(3);
    }


    public synchronized void playerAskForHelp() {
        if (state == ControllerState.WAITING_FOR_USER_MULTIPV
                || state == ControllerState.WAITING_FOR_ENGINE_MULTIPV) {
            gui.onGameEvent(GameStatus.ILLEGAL, "正在寻求帮助，请稍候或闪电出着");
            return;
        }
        if (!isHumanTurnNow()) {
            gui.onGameEvent(GameStatus.ILLEGAL, "己方出招时方可寻求帮助");
            return;
        }

        // 开始搜索中
        if (settings.getGo_infinite()) {
            gui.onGameEvent(GameStatus.UPDATEUI, "无限寻求帮助中, 须闪电出着!");
        } else {
            gui.onGameEvent(GameStatus.UPDATEUI, "寻求帮助中...");
        }
        // 人发起的帮助搜索，结果由 notifySearchResult 按用户分支处理
        state = ControllerState.WAITING_FOR_USER_MULTIPV;
        multiPVs = Collections.emptyList();

        // trigger searchrequest, engine will call notifySearchResult for bestmove
        queueSearch(3);
    }

    /*
     * 以初始局面+着法历史的方式发起搜索请求，便于引擎识别重复局面
     */
    private void queueSearch(int numPV) {
        searchStartTime = System.currentTimeMillis();
        Board startBoard = game.history.isEmpty() ? game.currentBoard : game.history.get(0).move.board;
        SearchRequest sr = SearchRequest.searchRequest(
                searchId++,
                startBoard,
                game.getMoveList(),
                new Board(game.currentBoard),
                null,
                false,
                engineName,
                numPV);
        GcLog.d("queueSearch: searchId=" + searchId + " fen=" + startBoard.toFENString() + " moves=" + game.getMoveList().size());
        player.queueSearchRequest(sr);
        GcLog.d("queueSearch: request queued");
    }

    public synchronized void evalCurrentBoard() {
        com.zfdang.chess.controllers.GcLog.d("evalCurrentBoard enter: state=" + state);
        if (!(state == ControllerState.WAITING_FOR_USER || state == ControllerState.WAITING_FOR_ENGINE)) {
            com.zfdang.chess.controllers.GcLog.d("evalCurrentBoard: bad state, skip");
            gui.onGameEvent(GameStatus.ILLEGAL, "等待出着时方可评估局面");
            return;
        }

        preEvalState = state;
        state = ControllerState.WAITING_FOR_EVAL;

        // trigger searchrequest, engine will call notifySearchResult for bestmove
        searchStartTime = System.currentTimeMillis();
        Board board = game.currentBoard;
        evalBoard = board;
        SearchRequest sr = SearchRequest.evalRequest(
                searchId++,
                board,
                engineName);
        player.queueSearchRequest(sr);
        // result will be returned by notifyEvalResult
    }

    public void touchPosition(@NotNull Position pos) {
        if (game.startPos == null) {
            // start position is empty
            int piece = game.currentBoard.getPieceByPosition(pos);
            if (Piece.isValid(piece)) {
                // 引擎回合不允许选子（如电脑执红先走前选黑子），与走子校验保持同一口径
                if (isComputerPlaying && isEngineTurnNow()) {
                    gui.onGameEvent(GameStatus.ILLEGAL, "等待对方出招");
                    return;
                }
                // 选子阶段即校验颜色：轮到谁走只能选谁的子
                boolean redToMove = game.currentBoard.bRedGo;
                if (redToMove && !Piece.isRed(piece)) {
                    gui.onGameEvent(GameStatus.ILLEGAL, "该红方出着");
                    return;
                } else if (!redToMove && Piece.isRed(piece)) {
                    gui.onGameEvent(GameStatus.ILLEGAL, "该黑方出着");
                    return;
                }
                // and the piece is valid
                game.setStartPos(pos);
                gui.onGameEvent(GameStatus.SELECT, "选择棋子");
            }
        } else {
            // startPos is not empty
            if (game.startPos.equals(pos)) {
                // click the same position, unselect
                game.clearStartPos();
                gui.onGameEvent(GameStatus.SELECT, "取消选择棋子");
                return;
            }

            // 判断pos是否是合法的move的终点
            Move tempMove = new Move(game.startPos, pos, game.currentBoard);
            boolean valid = Rule.isValidMove(tempMove, game.currentBoard);
            if (valid) {
                game.setEndPos(pos);

                // 非走棋状态，不允许走棋
                if (state != ControllerState.WAITING_FOR_USER && state != ControllerState.WAITING_FOR_ENGINE) {
                    Log.d("GameController", "非走棋状态，请稍候");
                    gui.onGameEvent(GameStatus.ILLEGAL, "非走棋状态，请稍候...");

                    // reset start/end position
                    game.startPos = null;
                    game.endPos = null;
                    return;
                }

                int piece = game.currentBoard.getPieceByPosition(game.startPos);
                // 确保棋子颜色和当前状态匹配（人机模式下轮到电脑时不允许人碰棋子）
                if (isComputerPlaying && isEngineTurnNow()) {
                    gui.onGameEvent(GameStatus.ILLEGAL, "电脑思考中，请稍候...");
                    game.startPos = null;
                    game.endPos = null;
                    return;
                }
                boolean redToMove = game.currentBoard.bRedGo;
                if (redToMove && !Piece.isRed(piece)) {
                    gui.onGameEvent(GameStatus.ILLEGAL, "该红方出着");
                    game.startPos = null;
                    game.endPos = null;
                    return;
                } else if (!redToMove && Piece.isRed(piece)) {
                    gui.onGameEvent(GameStatus.ILLEGAL, "该黑方出着");
                    game.startPos = null;
                    game.endPos = null;
                    return;
                }

                doMoveAndUpdateStatus(null);
            } else {
                // clear start position
                game.startPos = null;
                gui.onGameEvent(GameStatus.ILLEGAL, "非法走法");
            }
        }
    }

    public void stopSearchNow() {
        // send "stop" to engine for "bestmove"
        if (state == ControllerState.WAITING_FOR_ENGINE_BESTMV || state == ControllerState.WAITING_FOR_ENGINE_MULTIPV) {
            player.stopSearch();
            gui.onGameEvent(GameStatus.SELECT, "闪电出着中...");
        } else if (state == ControllerState.WAITING_FOR_USER_MULTIPV) {
            player.stopSearch();
            gui.onGameEvent(GameStatus.SELECT, "停止寻求帮助...");
        } else {
            gui.onGameEvent(GameStatus.ILLEGAL, "无搜索任务");
        }
    }

    public void applyEngineSetting() {
        // apply settings to engine
        Log.d("GameController", "Apply engine settings");
        Map<String, String> uciOptions = new HashMap<>();
        uciOptions.put("Hash", String.valueOf(settings.getHash_size()));
        player.setUCIOptions(uciOptions);
    }


    public void computerMovePiece(String bestmove) {
        // validate the move
        Move move = new Move(game.currentBoard);
        boolean result = move.fromUCCIString(bestmove);

        GcLog.d("computerMovePiece: bestmove=" + bestmove + " valid=" + result);
        if (result) {
            Log.d("GameController", "computer move: " + move.getChsString());

            game.setStartPos(move.fromPosition);
            game.setEndPos(move.toPosition);
            doMoveAndUpdateStatus(null);
        } else {
            Log.e("GameController", "Invalid move: " + bestmove);
            gui.onGameEvent(GameStatus.LOSE, "无路可走");
        }
    }

    public void processMultiPVInfos(String bestmove) {
        if (closed) return;
        List<PvInfo> snapshot = multiPVs;
        for (PvInfo pv : snapshot) {
            Log.d("GameController", "PV: " + pv);
        }
        if (snapshot.isEmpty()) {
            Move m = new Move(game.currentBoard);
            if (!m.fromUCCIString(bestmove)) {
                Log.e("GameController", "Invalid move: " + bestmove);
                gui.onGameEvent(GameStatus.LOSE, "无路可走");
                return;
            }
            ArrayList<Move> moves = new ArrayList<>();
            moves.add(m);
            snapshot = Collections.singletonList(new PvInfo(0, 0, 0, 0, 0, 0, 0, 0,
                    false, false, false, moves));
        }
        // Keep the list used for numbered choices separate from ongoing engine updates.
        suggestedPVs = snapshot;
        game.generateSuggestedMoves(snapshot);
        gui.onGameEvent(GameStatus.MULTIPV, "选择编号或直接移动棋子：");
    }

    public void selectMultiPV(int index) {
        List<PvInfo> snapshot = suggestedPVs;
        if (index < 0 || index >= snapshot.size()) return;
        Move move = game.getSuggestedMove(index);
        if (move != null) {
            game.clearSuggestedMoves();
            game.startPos = move.fromPosition;
            game.endPos = move.toPosition;
            doMoveAndUpdateStatus(snapshot.get(index));
        }
    }


    public void doMoveAndUpdateStatus(PvInfo pvinfo) {
        // game.startPos & game.endPos should be ready
        if (state != ControllerState.WAITING_FOR_USER && state != ControllerState.WAITING_FOR_ENGINE) {
            Log.e("GameController", "Invalid state, doMoveAndUpdateStatus should not be called now." + state);
            return;
        }

        // 已将死或困毙，只能悔棋或开新局
        if (game.isGameOver) {
            gui.onGameEvent(GameStatus.ILLEGAL, GAME_OVER_HINT);
            game.clearStartPos();
            game.endPos = null;
            return;
        }

        // check piece color to move（按真实轮走方 bRedGo 判断，兼容人执黑/电脑执红）
        int piece = game.currentBoard.getPieceByPosition(game.startPos);
        boolean redToMove = game.currentBoard.bRedGo;
        GcLog.d("doMove: color check pieceRed=" + Piece.isRed(piece) + " redToMove=" + redToMove);
        if (Piece.isRed(piece) != redToMove) {
            Log.e("GameController", "Invalid move, piece color is not match");
            if (redToMove) {
                gui.onGameEvent(GameStatus.ILLEGAL, "该红方出着");
            } else {
                gui.onGameEvent(GameStatus.ILLEGAL, "该黑方出着");
            }
            // reset start/end position
            game.startPos = null;
            game.endPos = null;
            return;
        }

        game.movePiece();

        // update controller status
        toggleTurn();

        // update game status after the move
        GameStatus status = game.updateGameStatus();

        // clear multipv status
        game.clearSuggestedMoves();

        // send notification to GUI
        if (status == GameStatus.CHECKMATE) {
            gui.onGameEvent(GameStatus.CHECKMATE, game.isStalemate ? "困毙！" : "将死！");
            // 对局结束：不再评估局面，也不再触发电脑自动走棋
            return;
        } else if (status == GameStatus.CHECK) {
            gui.onGameEvent(GameStatus.CHECK, "将军！");
        } else {
            if (pvinfo != null) {
                Log.d("GameController", "PV: " + pvinfo);

                // show multiPV infos
                Board b = new Board(game.currentBoard);
                ArrayList<Move> moves = pvinfo.pv;
                String desc = "预测着法：";
                for (int i = 1; i < moves.size() && i <= 4; i++) {
                    Move m = moves.get(i);
                    m.setBoard(b);
                    b.doMove(m);
                    desc += m.getChsString() + " ";
                }
                gui.onGameEvent(GameStatus.MOVE, desc);
            } else {
                gui.onGameEvent(GameStatus.MOVE, game.getLastMoveDesc());
            }
        }

        // start request to evaluate board status
        // result will be returned by notifyEvalResult
        com.zfdang.chess.controllers.GcLog.d("doMove done: state=" + state + " bRedGo=" + game.currentBoard.bRedGo + " -> evalCurrentBoard");
        evalCurrentBoard();
    }

    protected void toggleTurn() {
        // 走子后轮走方已由棋盘 bRedGo 翻转，按执棋方重新推导等待状态
        setSatate(game.currentBoard.bRedGo);
    }

    @Override
    public void reportEngineError(String errMsg) {
        Log.d("GameController", "Engine error: " + errMsg);
        if (closed) return;
        mainHandler.post(() -> {
            if (closed) return;
            if (state == ControllerState.WAITING_FOR_EVAL) state = preEvalState;
            else if (state != ControllerState.MANUAL_MODE && game != null && game.currentBoard != null)
                setSatate(game.currentBoard.bRedGo);
            // The raw message is a Java/exception string; keep it in the log and give the user
            // an actionable hint. A stopped engine is not restarted automatically by design:
            // repeated restarts against a broken engine would just thrash.
            gui.onGameEvent(GameStatus.ILLEGAL, "引擎已停止，请重新走子或开新局");
        });
    }

    @Override
    public void notifyEngineName(String engineName) {
        Log.d("GameController", "Engine name: " + engineName);
        this.engineInfo = engineName;
    }

    @Override
    public void notifyDepth(int id, int depth) {
        Log.d("GameController", "Depth: " + depth);
    }

    @Override
    public void notifyCurrMove(int id, Board board, Move m, int moveNr) {
        Log.d("GameController", "Current move: " + m.getUCCIString());
    }

    @Override
    public void notifyPV(int id, Board board, ArrayList<PvInfo> pvInfos, Move ponderMove) {
        if (closed) return;
        // Serialize publication with close(), without holding this lock while shutting down.
        synchronized (pvLock) {
            if (closed) return;
            multiPVs = Collections.unmodifiableList(new ArrayList<>(pvInfos));
        }
        Log.d("GameController", "PV: " + multiPVs);
    }

    @Override
    public void notifyStats(int id, long nodes, int nps, long tbHits, int hash, int time, int seldepth) {
        Log.d("GameController", "Stats: nodes=" + nodes + ", nps=" + nps + ", tbHits=" + tbHits + ", hash=" + hash + ", time=" + time + ", seldepth=" + seldepth);
    }

    @Override
    public void notifyBookInfo(int id, String bookInfo, ArrayList<Move> moveList, String eco, int distToEcoTree) {
        Log.d("GameController", "Book info: bookInfo=" + bookInfo + ", eco=" + eco + ", distToEcoTree=" + distToEcoTree);
    }

    @Override
    public void notifySearchResult(int searchId, String bestMove, String nextPonderMove) {
        if (closed) return;
        // engine返回bestmove, 有3种情况，一种是电脑搜索走棋的结果，一种是红方寻求帮助的结果，一种是电脑被强制要求变着的结果

        Log.d("GameController", "Search result: bestMove=" + bestMove + ", nextPonderMove=" + nextPonderMove);
        searchEndTime = System.currentTimeMillis();

        if (state == ControllerState.WAITING_FOR_USER_MULTIPV) {
            // 红方寻求帮助
            // 把multiPV的结果显示在界面上，让用户选择
            state = ControllerState.WAITING_FOR_USER;
            gui.runOnUIThread(() -> processMultiPVInfos(bestMove));
        } else if (state == ControllerState.WAITING_FOR_ENGINE_MULTIPV) {
            // 电脑被强制要求变着
            // 把multiPV的结果显示在界面上，让用户选择
            state = ControllerState.WAITING_FOR_ENGINE;
            gui.runOnUIThread(() -> processMultiPVInfos(bestMove));
        } else if (state == ControllerState.WAITING_FOR_ENGINE_BESTMV) {
            // 电脑发起的请求，走下一步棋子
            state = ControllerState.WAITING_FOR_ENGINE;
            // 如果设置了引擎的随机性，则从multiPV中随机选择一个着法。这个只针对前12步有效，后期不让电脑随机选择，否则棋力降低太多
            List<PvInfo> snapshot = multiPVs;
            if (settings.getRandom_move() && !snapshot.isEmpty() && game.currentBoard.rounds <= random_before_max_rounds) {
                int idx = (int) (Math.random() * snapshot.size());
                String randomMove = snapshot.get(idx).pv.get(0).getUCCIString();
                gui.runOnUIThread(() -> computerMovePiece(randomMove));
                Log.d("GameController", "Search result: bestMove=" + bestMove + ", randomMove=" + randomMove);
            } else {
                gui.runOnUIThread(() -> computerMovePiece(bestMove));
                Log.d("GameController", "Search result: bestMove=" + bestMove);
            }
        }
    }

    @Override
    public void notifyEvalResult(int searchId, float eval) {
        com.zfdang.chess.controllers.GcLog.d("notifyEvalResult: eval=" + eval + " closed=" + closed
                + " auto=" + isAutoPlay + " engineTurn=" + isEngineTurnNow());
        if (closed) return;
        Log.d("GameController", "Eval result: eval=" + eval);
        // 写回被评估的那个局面(而不是此刻的currentBoard)，并在UI线程上写，与movePiece串行
        Board evaluated = evalBoard;
        gui.runOnUIThread(() -> {
                if (closed) return;
                if (evaluated != null) {
                    evaluated.score = eval;
                }
                gui.onGameEvent(GameStatus.UPDATEUI);
        });

        // restore state
        state = preEvalState;

        // auto nextstep for computer if applicable
        if (isAutoPlay && isEngineTurnNow()) {
            // 因为要显示预测着法，所以让电脑延迟500s走棋
            mainHandler.postDelayed(this::computerForward, 500);
        }

    }

    @Override
    public void notifyEngineInitialized() {
        Log.d("GameController", "Engine initialized");
    }

    public void swapSides() {
        // search openbook first
        long vkey = game.currentBoard.getZobrist(isRedTurn());
        List<BookData> bookData = bhBook.query(vkey, isRedTurn(), OpenBook.SortRule.BEST_SCORE);
        // iterate bookData one by one
        for (BookData bd : bookData) {
            Log.d("GameController", "Openbook hit: " + bd.getMove());
        }
    }

    public int getMultiPVSize() {
        return suggestedPVs.size();
    }

    public void saveGameStatus() {
        // save game status
        try {
            game.saveToFile(ChessApp.getContext());
            Log.d("GameController", "Game status saved");
        } catch (IOException e) {
            Log.e("GameController", "Failed to save game status" + e);
        }
    }

    public void loadGameStatus() {
        // load game status
        try {
            game = Game.loadFromFile(ChessApp.getContext());
            setSatate(game.currentBoard.bRedGo);
            Log.d("GameController", "Game status loaded");
            // 恢复对局时若轮到引擎（含电脑执红先走），自动出手
            maybeEngineForward();
        } catch (IOException e) {
            Log.e("GameController", "Failed to load game status: " + e);
        } catch (ClassNotFoundException e) {
            Log.e("GameController", "Failed to load game status: " + e);
        }
    }
}

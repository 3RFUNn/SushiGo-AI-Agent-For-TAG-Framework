package players.alphaAMAF;

import core.AbstractGameState;
import core.AbstractPlayer;
import core.actions.AbstractAction;
import core.interfaces.IStateHeuristic;

import java.util.List;
import java.util.Random;


/**
 * This is a simple version of MCTS that may be useful for newcomers to TAG and MCTS-like algorithms
 * It strips out some of the additional configuration of MCTSPlayer. It uses BasicTreeNode in place of
 * SingleTreeNode.
 */
public class alphaAMAFPlayer extends AbstractPlayer {

    public alphaAMAFPlayer() {
        this(System.currentTimeMillis());
    } //是无参构造函数，默认使用当前时间戳作为种子来生成随机数。

    public alphaAMAFPlayer(long seed) {
        super(new alphaAMAF(), "αAMAF");
        // for clarity we create a new set of parameters here, but we could just use the default parameters
        parameters.setRandomSeed(seed); //接受一个长整型的 seed 作为参数
        rnd = new Random(seed);

        // These parameters can be changed, and will impact the Basic MCTS algorithm
        alphaAMAF params = getParameters();
        params.K = Math.sqrt(2); //这是 UCB1 算法中的常量，平衡探索和利用的参数。
        params.rolloutLength = 10; //决定每次模拟的最大步数（即 rollout 的长度）。
        params.maxTreeDepth = 5; //限制搜索树的最大深度。
        params.epsilon = 1e-6; //防止浮点数精度问题的小量。

    }

    public alphaAMAFPlayer(alphaAMAF params) {
        super(params, "αAMAF");
        rnd = new Random(params.getRandomSeed());
    }

    @Override
    public AbstractAction _getAction(AbstractGameState gameState, List<AbstractAction> actions) {
        // Search for best action from the root
        alphaAMAFTreeNode root = new alphaAMAFTreeNode(this, null, gameState, rnd);

        // mctsSearch does all of the hard work
        root.mctsSearch();

        // Return best action
        return root.bestAction();
    }

    @Override
    public alphaAMAF getParameters() {
        return (alphaAMAF) parameters;
    } //获取外部parameters配制

    public void setStateHeuristic(IStateHeuristic heuristic) {
        getParameters().heuristic = heuristic;
    }


    @Override
    public String toString() {
        return "αAMAF";
    }

    @Override
    public alphaAMAFPlayer copy() {
        return this;
    }
}
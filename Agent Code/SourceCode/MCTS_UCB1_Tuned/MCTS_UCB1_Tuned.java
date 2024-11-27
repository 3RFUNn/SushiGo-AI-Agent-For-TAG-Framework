package players.MCTS_UCB1_Tuned;

import core.AbstractGameState;
import core.interfaces.IStateHeuristic;
import players.PlayerParameters;

import java.util.Arrays;


public class MCTS_UCB1_Tuned extends PlayerParameters { //playersparameters extends evaluation/optimisation/TunableParameters extends core/AbstractParameters

    public double K = Math.sqrt(2);
    public int rolloutLength = 10; // assuming we have a good heuristic
    public int maxTreeDepth = 100; // effectively no limit
    public double epsilon = 1e-6;
    public IStateHeuristic heuristic = AbstractGameState::getHeuristicScore;

    public MCTS_UCB1_Tuned() { //这意味着它继承了与玩家参数相关的基本行为，并可以利用父类的功能来存储和调整参数。
        addTunableParameter("K", Math.sqrt(2), Arrays.asList(0.0, 0.1, 1.0, Math.sqrt(2), 3.0, 10.0));
        addTunableParameter("rolloutLength", 10, Arrays.asList(0, 3, 10, 30, 100));
        addTunableParameter("maxTreeDepth", 100, Arrays.asList(1, 3, 10, 30, 100));
        addTunableParameter("epsilon", 1e-6);
        addTunableParameter("heuristic", (IStateHeuristic) AbstractGameState::getHeuristicScore);

    }

    @Override
    public void _reset() { //该方法在需要重置参数时使用。通过调用 getParameterValue 来获取各个参数的当前值，并将其应用到类的属性中。
        super._reset();
        K = (double) getParameterValue("K");
        rolloutLength = (int) getParameterValue("rolloutLength");
        maxTreeDepth = (int) getParameterValue("maxTreeDepth");
        epsilon = (double) getParameterValue("epsilon");
        heuristic = (IStateHeuristic) getParameterValue("heuristic"); //启发式评估函数，默认为 AbstractGameState::getHeuristicScore，即利用游戏状态中的默认启发式评分函数。
    }

    @Override
    protected MCTS_UCB1_Tuned _copy() { //这是 PlayerParameters 的拷贝方法，用于复制参数对象的当前状态，生成一个新的 BasicMCTSParams 对象。这个方法确保所有已经注册的参数都被正确复制。
        // All the copying is done in TunableParameters.copy()
        // Note that any *local* changes of parameters will not be copied
        // unless they have been 'registered' with setParameterValue("name", value)
        return new MCTS_UCB1_Tuned();
    }

    public IStateHeuristic getHeuristic() {
        return heuristic;
    }

    @Override
    public MCTS_UCB1_TunedPlayer instantiate() {
        return new MCTS_UCB1_TunedPlayer((MCTS_UCB1_Tuned) this.copy());
    } //该方法用于创建并返回一个新的 BasicMCTSPlayer 实例。它通过复制当前参数对象 this.copy()，然后将这些参数传递给 BasicMCTSPlayer，从而确保新的玩家对象具有与当前参数相同的配置。

}

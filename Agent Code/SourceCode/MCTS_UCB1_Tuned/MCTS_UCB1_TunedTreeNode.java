package players.MCTS_UCB1_Tuned;

import core.AbstractGameState;
import core.actions.AbstractAction;
import players.PlayerConstants;
import players.simple.RandomPlayer;
import utilities.ElapsedCpuTimer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static java.util.stream.Collectors.toList;
import static players.PlayerConstants.*;
import static utilities.Utils.noise;

class MCTS_UCB1_TunedTreeNode {
    // Root node of tree
    MCTS_UCB1_TunedTreeNode root;
    // Parent of this node
    MCTS_UCB1_TunedTreeNode parent;
    // Children of this node
    Map<AbstractAction, MCTS_UCB1_TunedTreeNode> children = new HashMap<>(); //是一个 Map<AbstractAction, BasicTreeNode>，存储当前节点下的子节点。键为动作（AbstractAction），值为其对应的子节点。
    // Depth of this node
    final int depth;

    // Total value of this node
    private double totValue; //累积值，表示从这个节点及其子树中的模拟回传的总得分。
    // Total value of squared value
    public double totValueSquared; /**/
    // Number of visits
    private int nVisits; //表示访问次数，跟踪节点被访问的频率，用于计算 UCB1 算法中的利用部分。
    // Number of FM calls and State copies up until this node
    private int fmCallsCount; //表示到当前节点的 Forward Model 调用次数，用于约束预算或其他停止条件。
    // Parameters guiding the search
    private MCTS_UCB1_TunedPlayer player; //关联的 BasicMCTSPlayer 对象，包含 MCTS 参数和前向模型（Forward Model）。
    private Random rnd; //随机数生成器，用于选择动作时的随机性。
    private RandomPlayer randomPlayer = new RandomPlayer();

    // State in this node (closed loop)
    private AbstractGameState state; //当前节点对应的游戏状态，通过前向模型和执行动作来生成新的游戏状态。

    protected MCTS_UCB1_TunedTreeNode(MCTS_UCB1_TunedPlayer player, MCTS_UCB1_TunedTreeNode parent, AbstractGameState state, Random rnd) {
        this.player = player;
        this.fmCallsCount = 0;
        this.parent = parent;
        this.root = parent == null ? this : parent.root;
        totValue = 0.0;
        totValueSquared = 0.0; // 初始化平方和为0 /*添加的内容*/
        setState(state);
        if (parent != null) {
            depth = parent.depth + 1;
        } else {
            depth = 0;
        }
        this.rnd = rnd;
        randomPlayer.setForwardModel(player.getForwardModel());
    }

    /**
     * Performs full MCTS search, using the defined budget limits.
     */
    void mctsSearch() { //MCTS 搜索的主循环，执行整个搜索过程。

        MCTS_UCB1_Tuned params = player.getParameters(); //获取与当前玩家（player）相关的 MCTS 参数。params 包含了搜索预算、树的最大深度等配置。

        // Variables for tracking time budget
        double avgTimeTaken; //记录每次迭代所花费的平均时间。
        double acumTimeTaken = 0; //记录总的时间消耗。
        long remaining; //剩余的时间预算（当使用时间预算时）。
        int remainingLimit = params.breakMS; //允许的最小剩余时间，当剩余时间小于此值时停止搜索。
        ElapsedCpuTimer elapsedTimer = new ElapsedCpuTimer(); //用于跟踪总时间消耗的计时器。
        if (params.budgetType == BUDGET_TIME) { //如果预算类型是时间（BUDGET_TIME），则将搜索的总时间预算（params.budget）设置给计时器。
            elapsedTimer.setMaxTimeMillis(params.budget);
        }

        // Tracking number of iterations for iteration budget
        int numIters = 0; //用于跟踪完成的 MCTS 迭代次数。

        boolean stop = false; //stop 用于控制主循环的停止条件，当 stop 为 true 时，循环结束。

        while (!stop) {
            // New timer for this iteration
            ElapsedCpuTimer elapsedTimerIteration = new ElapsedCpuTimer(); //每次迭代都重新初始化一个新的计时器，跟踪该迭代的时间消耗。

            // Selection + expansion: navigate tree until a node not fully expanded is found, add a new node to the tree
            MCTS_UCB1_TunedTreeNode selected = treePolicy(); //从当前根节点沿着树向下遍历，直到找到尚未完全扩展的节点，然后扩展一个新的节点。
            // Monte carlo rollout: return value of MC rollout from the newly added node
            double delta = selected.rollOut(); //对选择出的节点执行蒙特卡洛模拟（Monte Carlo Rollout），从该节点的状态随机进行游戏，直到游戏结束或达到指定深度。rollOut() 返回模拟的得分 delta。
            // Back up the value of the rollout through the tree
            selected.backUp(delta); //将蒙特卡洛模拟的结果（delta）回溯到父节点及其祖先节点，更新这些节点的累积值和访问次数。
            // Finished iteration
            numIters++; //每完成一次迭代，增加迭代次数。

            // Check stopping condition
            PlayerConstants budgetType = params.budgetType; //获取当前使用的预算类型（时间、迭代次数或前向模型调用次数）。
            if (budgetType == BUDGET_TIME) { //如果使用时间预算（BUDGET_TIME），则累加本次迭代所消耗的时间，计算平均每次迭代的耗时 avgTimeTaken，然后计算剩余时间。
                // Time budget
                acumTimeTaken += (elapsedTimerIteration.elapsedMillis());
                avgTimeTaken = acumTimeTaken / numIters;
                remaining = elapsedTimer.remainingTimeMillis(); //如果剩余时间 remaining 小于两倍的平均迭代耗时（即估计无法再完成一轮完整的迭代）或者小于设定的剩余时间限制（remainingLimit），则停止搜索。
                stop = remaining <= 2 * avgTimeTaken || remaining <= remainingLimit;
            } else if (budgetType == BUDGET_ITERATIONS) { //如果使用迭代次数预算（BUDGET_ITERATIONS），则检查迭代次数是否达到设定的迭代次数上限（params.budget），是则停止搜索。
                // Iteration budget
                stop = numIters >= params.budget;
            } else if (budgetType == BUDGET_FM_CALLS) { //如果使用前向模型调用次数预算（BUDGET_FM_CALLS），则检查前向模型调用次数是否超出预算，是则停止搜索。
                // FM calls budget
                stop = fmCallsCount > params.budget;
            }
        }
    }

    /**
     * Selection + expansion steps.
     * - Tree is traversed until a node not fully expanded is found.
     * - A new child of this node is added to the tree.
     *
     * @return - new node added to the tree.
     */
    private MCTS_UCB1_TunedTreeNode treePolicy() { //树的选择策略，负责遍历树结构，直到找到一个尚未完全扩展的节点。扩展此节点并返回。

        MCTS_UCB1_TunedTreeNode cur = this; //cur 表示当前节点，初始时设置为调用该方法的根节点（this）。

        // Keep iterating while the state reached is not terminal and the depth of the tree is not exceeded
        while (cur.state.isNotTerminal() && cur.depth < player.getParameters().maxTreeDepth) { //这是一个循环，遍历树结构。该循环继续执行，直到达到以下两个停止条件中的任意一个：当前状态是否为非终止状态（即游戏还没结束），确保在非终止状态下继续扩展。或当前节点的深度小于玩家设置的最大树深度，避免树的深度超过预定值。
            if (!cur.unexpandedActions().isEmpty()) { //返回当前节点中还未被扩展的动作列表。如果这个列表不为空，意味着当前节点还可以扩展出新的子节点。
                // We have an unexpanded action
                cur = cur.expand(); //扩展当前节点，创建一个新的子节点并返回它。扩展意味着从当前节点选择一个未扩展的动作，并基于该动作生成新的节点。
                return cur;
            } else { //如果当前节点没有未扩展的动作（即当前节点已经完全扩展）
                // Move to next child given by UCT function
                AbstractAction actionChosen = cur.ucb(); //使用 UCT（上置信界）选择下一个要访问的子节点对应的动作。UCT 是一种常用的选择策略，它在平衡探索与利用之间取得权衡，既考虑到不确定性较大的子节点，也考虑到已知较好的子节点。
                cur = cur.children.get(actionChosen); //选择使用 UCT 算法返回的动作所对应的子节点，cur 移动到该子节点继续遍历。
            }
        }

        return cur;
    }


    private void setState(AbstractGameState newState) {
        state = newState;
        if (newState.isNotTerminal())
            for (AbstractAction action : player.getForwardModel().computeAvailableActions(state, player.getParameters().actionSpace)) {
                children.put(action, null); // mark a new node to be expanded
            }
    }

    /**
     * @return A list of the unexpanded Actions from this State
     */
    private List<AbstractAction> unexpandedActions() {
        return children.keySet().stream().filter(a -> children.get(a) == null).collect(toList());
    } //children 是一个 Map<AbstractAction, BasicTreeNode>，其中键是 AbstractAction，表示动作，值是 BasicTreeNode，表示扩展后的子节点。
    //children.keySet() 返回这个 Map 中的所有键，也就是所有与当前节点相关的动作。
    //.stream() 将 children.keySet() 转换为流操作，以便应用函数式编程的过滤。
    //filter(a -> children.get(a) == null) 是一个过滤操作，它筛选出所有尚未扩展的动作。即：
    //  children.get(a) 获取与动作 a 关联的子节点。
    //  如果 children.get(a) == null，意味着这个动作 a 尚未扩展为一个子节点。
    //.collect(toList()) 将经过过滤的动作收集为一个 List<AbstractAction>，并将其返回。

    /**
     * Expands the node by creating a new random child node and adding to the tree.
     *
     * @return - new child node.
     */
    private MCTS_UCB1_TunedTreeNode expand() { //扩展当前节点，随机选择一个未选择的动作，并创建一个新的子节点，将其添加到树中。
        // Find random child not already created
        Random r = new Random(player.getParameters().getRandomSeed()); //创建一个随机数生成器，用来从未扩展的动作列表中随机选择一个动作。这里用的是 player.getParameters().getRandomSeed()，确保随机性可以通过固定的种子进行控制，便于复现实验结果。返回（设置好或随机生成的）Randomseed
        // pick a random unchosen action
        List<AbstractAction> notChosen = unexpandedActions(); //获取当前节点中还未被选择并扩展的动作列表，存储在 notChosen 中。这些动作可以用来生成新的子节点。
        AbstractAction chosen = notChosen.get(r.nextInt(notChosen.size())); //r.nextInt(notChosen.size())：使用随机数生成器 r 从未扩展的动作中随机选择一个动作的索引。notChosen.get(...)：根据生成的随机索引，从未扩展动作列表中选择该动作，并将其存储在 chosen 中。

        // copy the current state and advance it using the chosen action
        // we first copy the action so that the one stored in the node will not have any state changes
        AbstractGameState nextState = state.copy(); //对当前的游戏状态 state 进行深拷贝，生成一个独立的副本 nextState。这样可以确保当前节点的状态不会被修改。
        advance(nextState, chosen.copy()); //使用选中的动作 chosen（也进行了拷贝操作）推进游戏状态。advance() 方法基于所选动作更新游戏状态，模拟该动作的执行。

        // then instantiate a new node
        MCTS_UCB1_TunedTreeNode tn = new MCTS_UCB1_TunedTreeNode(player, this, nextState, rnd); //创建一个新的树节点 tn。新节点基于以下参数初始化
        children.put(chosen, tn); //将新扩展的子节点 tn 以及对应的动作 chosen 存储在当前节点的 children（子节点集合）中，建立动作和子节点的映射。
        return tn;
    }

    /**
     * Advance the current game state with the given action, count the FM call and compute the next available actions.
     *
     * @param gs  - current game state
     * @param act - action to apply
     */
    private void advance(AbstractGameState gs, AbstractAction act) { //将游戏状态推进到下一步，即应用给定的动作，并计算下一个状态。
        player.getForwardModel().next(gs, act); //gs：表示当前游戏状态，类型为 AbstractGameState，是游戏的抽象表示。 act：表示要执行的动作，类型为 AbstractAction，是对当前状态执行的动作。
        root.fmCallsCount++; //root.fmCallsCount++ 增加根节点的 fmCallsCount 计数器。
    } //player.getForwardModel() 返回当前玩家使用的前向模型（ForwardModel），这是负责模拟游戏进展的逻辑类。
    //next(gs, act) 调用前向模型的 next 方法，将当前游戏状态 gs 和动作 act 作为参数，执行该动作并将游戏状态推进到下一步。这一步会模拟应用某个动作后的游戏状态变化，决定游戏的下一步是什么。

    private AbstractAction ucb() { //UCB1 算法的实现。通过权衡利用（选择访问次数最多的子节点）和探索（选择访问次数较少的节点），选择最佳的子节点进行扩展。该方法还会根据是玩家回合还是对手回合，调整计算公式。
        // Find child with highest UCB value, maximising for ourselves and minimizing for opponent
        AbstractAction bestAction = null; //bestAction 初始化为 null，用于存储当前找到的最佳动作。
        double bestValue = -Double.MAX_VALUE; //初始化为负的最大值，作为比较的起始点。
        MCTS_UCB1_Tuned params = player.getParameters(); //获取当前玩家的参数 params，以便后续使用。

        for (AbstractAction action : children.keySet()) { //遍历当前节点的所有子节点（即所有可执行的动作）。
            MCTS_UCB1_TunedTreeNode child = children.get(action); //从 children 映射中获取当前动作对应的子节点。
            if (child == null) //如果 child 为 null，则抛出一个断言错误，表示逻辑错误。
                throw new AssertionError("Should not be here");
            else if (bestAction == null) //如果 bestAction 仍为 null，则将当前动作设置为 bestAction，即第一个被检查的动作。
                bestAction = action;

            // Find child value
            double hvVal = child.totValue; //获取子节点的总值 totValue，
            double childValue = hvVal / (child.nVisits + params.epsilon); //计算当前子节点的值 childValue。这表示子节点的平均得分，params.epsilon 是一个小常数，防止除以零。

            //V(s,a) /*添加的内容*/
            // 计算 V(s, a)
            double variance = (child.totValueSquared / child.nVisits) - (childValue * childValue); // 计算方差
            variance = Math.max(variance, 0); // 确保方差非负
            double vSa = variance + Math.sqrt(2 * Math.log(root.fmCallsCount) / (child.nVisits + params.epsilon)); // 计算 V(s, a)

            // default to standard UCB
            double explorationTerm = params.K * Math.sqrt(Math.log(this.nVisits + 1) / (child.nVisits + params.epsilon) * Math.min(0.25, vSa));; //计算探索项 explorationTerm，根据访问次数计算探索力度。params.K 是一个常数，用于调整探索程度。
            // unless we are using a variant

            // Find 'UCB' value
            // If 'we' are taking a turn we use classic UCB
            // If it is an opponent's turn, then we assume they are trying to minimise our score (with exploration)
            boolean iAmMoving = state.getCurrentPlayer() == player.getPlayerID(); //判断当前是否是玩家的回合，将结果存储在 iAmMoving 中。
            double uctValue = iAmMoving ? childValue : -childValue; //如果是玩家的回合，使用经典 UCB 方法，即直接使用 childValue；如果是对手的回合，假设对手试图最小化玩家的得分，因此使用 -childValue。
            uctValue += explorationTerm; //将探索项 explorationTerm 加到 uctValue 中。

            // Apply small noise to break ties randomly
            uctValue = noise(uctValue,params.epsilon, player.getRnd().nextDouble()); //通过 noise 方法在 uctValue 中添加小的随机噪声，帮助打破值相同的情况，从而随机选择一个动作。

            // Assign value
            if (uctValue > bestValue) { //如果当前的 uctValue 大于 bestValue，则更新 bestAction 为当前动作，并将 bestValue 更新为 uctValue。
                bestAction = action;
                bestValue = uctValue;
            }
        }

        if (bestAction == null) //如果在循环结束后 bestAction 仍然为 null，则抛出一个断言错误，表示逻辑错误。
            throw new AssertionError("We have a null value in UCT : shouldn't really happen!");

        root.fmCallsCount++;  // log one iteration complete //增加 root.fmCallsCount，记录完成了一次完整的迭代。
        return bestAction;
    }

    /**
     * Perform a Monte Carlo rollout from this node.
     *
     * @return - value of rollout.
     */
    private double rollOut() { //执行 Monte Carlo 模拟，从当前状态开始一直进行到游戏结束或达到指定深度。模拟完成后，评估终局状态，并返回一个启发式评分。
        int rolloutDepth = 0; // counting from end of tree //rolloutDepth 变量初始化为 0，用于计数，从树的末尾开始计算，表示当前模拟的深度。

        // If rollouts are enabled, select actions for the rollout in line with the rollout policy
        AbstractGameState rolloutState = state.copy(); //复制当前的游戏状态 state，以便在模拟中使用。这样可以确保在模拟过程中不会修改原始状态，保持其不变。
        if (player.getParameters().rolloutLength > 0) { //检查玩家的参数中是否启用了 rollouts，具体是看 rolloutLength 是否大于 0。如果启用，则进入以下循环。
            while (!finishRollout(rolloutState, rolloutDepth)) { //这个循环将持续进行，直到达到结束条件（由 finishRollout 方法决定）。该方法检查是否应停止模拟，可能基于当前状态或深度。
                AbstractAction next = randomPlayer.getAction(rolloutState, randomPlayer.getForwardModel().computeAvailableActions(rolloutState, randomPlayer.parameters.actionSpace)); //计算在当前状态下可用的动作。然后 randomPlayer.getAction 从这些可用的动作中随机选择一个动作。
                advance(rolloutState, next); //使用所选的 next 动作调用 advance 方法，将模拟的状态 rolloutState 推进到下一步。
                rolloutDepth++; //每次推进状态后，增加 rolloutDepth 的计数，表示模拟已进行一层。
            }
        }
        // Evaluate final state and return normalised score
        double value = player.getParameters().getHeuristic().evaluateState(rolloutState, player.getPlayerID()); //一旦模拟结束，使用玩家的启发式函数评估最终的 rolloutState。evaluateState 方法返回一个评估值，通常是基于游戏状态的评分。
        if (Double.isNaN(value)) //检查评估值 value 是否为 NaN（不是数字），如果是，则抛出 AssertionError，表示启发式值不合法，应该返回一个有效的数值。
            throw new AssertionError("Illegal heuristic value - should be a number");
        return value;
    }

    /**
     * Checks if rollout is finished. Rollouts end on maximum length, or if game ended.
     *
     * @param rollerState - current state
     * @param depth       - current depth
     * @return - true if rollout finished, false otherwise
     */
    private boolean finishRollout(AbstractGameState rollerState, int depth) {
        if (depth >= player.getParameters().rolloutLength)
            return true;

        // End of game
        return !rollerState.isNotTerminal();
    }

    /**
     * Back up the value of the child through all parents. Increase number of visits and total value.
     *
     * @param result - value of rollout to backup
     */
    private void backUp(double result) { //将模拟的结果值从当前节点向上回传到根节点，更新各个节点的访问次数和累积值。
        MCTS_UCB1_TunedTreeNode n = this;
        while (n != null) {
            n.nVisits++;
            n.totValue += result;
            n.totValueSquared += result * result; /*添加的内容*/
            n = n.parent;
        }
    }

    /**
     * Calculates the best action from the root according to the most visited node
     *
     * @return - the best AbstractAction
     */
    AbstractAction bestAction() { //根据访问次数选择最优的动作。通常在搜索结束时调用，选择访问次数最多的子节点对应的动作作为最终决策。

        double bestValue = -Double.MAX_VALUE;
        AbstractAction bestAction = null;

        for (AbstractAction action : children.keySet()) {
            if (children.get(action) != null) {
                MCTS_UCB1_TunedTreeNode node = children.get(action);
                double childValue = node.nVisits;

                // Apply small noise to break ties randomly
                childValue = noise(childValue, player.getParameters().epsilon, player.getRnd().nextDouble());

                // Save best value (highest visit count)
                if (childValue > bestValue) {
                    bestValue = childValue;
                    bestAction = action;
                }
            }
        }

        if (bestAction == null) {
            throw new AssertionError("Unexpected - no selection made.");
        }

        return bestAction;
    }

}

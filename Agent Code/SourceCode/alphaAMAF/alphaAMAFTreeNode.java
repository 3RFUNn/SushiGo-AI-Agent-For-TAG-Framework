package players.alphaAMAF;

import core.AbstractGameState;
import core.actions.AbstractAction;
import players.PlayerConstants;
import players.simple.RandomPlayer;
import utilities.ElapsedCpuTimer;

import java.util.*;

import static java.util.stream.Collectors.toList;
import static players.PlayerConstants.*;
import static utilities.Utils.noise;

class alphaAMAFTreeNode {
    // Root node of tree
    alphaAMAFTreeNode root;
    // Parent of this node
    alphaAMAFTreeNode parent;
    // Children of this node
    Map<AbstractAction, alphaAMAFTreeNode> children = new HashMap<>(); //是一个 Map<AbstractAction, BasicTreeNode>，存储当前节点下的子节点。键为动作（AbstractAction），值为其对应的子节点。

    // AMAF statistics /*添加的内容2*/
    Map<AbstractAction, Integer> amafVisits = new HashMap<>();
    Map<AbstractAction, Double> amafValues = new HashMap<>();

    Map<AbstractAction, Integer> opponentModel = new HashMap<>();

    List<AbstractAction> rolloutActions = new ArrayList<>();

    //alpha-beta cutting
    private double alpha = -Double.MAX_VALUE; // 当前节点的 alpha 值
    private double beta = Double.MAX_VALUE; // 当前节点的 beta 值

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
    private alphaAMAFPlayer player; //关联的 BasicMCTSPlayer 对象，包含 MCTS 参数和前向模型（Forward Model）。
    private Random rnd; //随机数生成器，用于选择动作时的随机性。
    private RandomPlayer randomPlayer = new RandomPlayer();

    // State in this node (closed loop)
    private AbstractGameState state; //当前节点对应的游戏状态，通过前向模型和执行动作来生成新的游戏状态。

    protected alphaAMAFTreeNode(alphaAMAFPlayer player, alphaAMAFTreeNode parent, AbstractGameState state, Random rnd) {
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

        alphaAMAF params = player.getParameters(); //获取与当前玩家（player）相关的 MCTS 参数。params 包含了搜索预算、树的最大深度等配置。

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
            alphaAMAFTreeNode selected = treePolicy(); //从当前根节点沿着树向下遍历，直到找到尚未完全扩展的节点，然后扩展一个新的节点。
            // Monte carlo rollout: return value of MC rollout from the newly added node
            double delta = selected.rollOut(); //对选择出的节点执行蒙特卡洛模拟（Monte Carlo Rollout），从该节点的状态随机进行游戏，直到游戏结束或达到指定深度。rollOut() 返回模拟的得分 delta。
            // Back up the value of the rollout through the tree
            selected.backUp(delta,rolloutActions); //将蒙特卡洛模拟的结果（delta）回溯到父节点及其祖先节点，更新这些节点的累积值和访问次数。
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
    private alphaAMAFTreeNode treePolicy() {
        alphaAMAFTreeNode cur = this;

        // Keep iterating while the state reached is not terminal and the depth of the tree is not exceeded
        while (cur.state.isNotTerminal() && cur.depth < player.getParameters().maxTreeDepth) {
            if (!cur.unexpandedActions().isEmpty()) {
                // We have an unexpanded action
                cur = cur.expand();
                return cur;
            } else {
                // Move to next child given by UCB function
                AbstractAction actionChosen = cur.ucb();
                alphaAMAFTreeNode child = cur.children.get(actionChosen);

                if (child == null)
                    throw new AssertionError("Should not be here");

                // Alpha-beta pruning logic
                if (cur.state.getCurrentPlayer() == player.getPlayerID()) { // Maximizing player
                    cur.alpha = Math.max(cur.alpha, child.totValue / (child.nVisits + player.getParameters().epsilon));

                    // Check if the beta condition is met
                    if (cur.alpha >= cur.beta) {
                        break; // Prune the remaining branches
                    }
                } else { // Minimizing player
                    cur.beta = Math.min(cur.beta, child.totValue / (child.nVisits + player.getParameters().epsilon));

                    // Check if the alpha condition is met
                    if (cur.beta <= cur.alpha) {
                        break; // Prune the remaining branches
                    }
                }

                cur = child; // Continue with the chosen child
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
    private alphaAMAFTreeNode expand() { //扩展当前节点，随机选择一个未选择的动作，并创建一个新的子节点，将其添加到树中。
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
        alphaAMAFTreeNode tn = new alphaAMAFTreeNode(player, this, nextState, rnd); //创建一个新的树节点 tn。新节点基于以下参数初始化
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
        boolean iAmMoving = gs.getCurrentPlayer() == player.getPlayerID();
        player.getForwardModel().next(gs, act); //gs：表示当前游戏状态，类型为 AbstractGameState，是游戏的抽象表示。 act：表示要执行的动作，类型为 AbstractAction，是对当前状态执行的动作。
        root.fmCallsCount++; //root.fmCallsCount++ 增加根节点的 fmCallsCount 计数器。
        if (!iAmMoving) {
            opponentModel.put(act, opponentModel.getOrDefault(act, 0) + 1);
        }
    } //player.getForwardModel() 返回当前玩家使用的前向模型（ForwardModel），这是负责模拟游戏进展的逻辑类。
    //next(gs, act) 调用前向模型的 next 方法，将当前游戏状态 gs 和动作 act 作为参数，执行该动作并将游戏状态推进到下一步。这一步会模拟应用某个动作后的游戏状态变化，决定游戏的下一步是什么。

    private AbstractAction ucb() {
        AbstractAction bestAction = null;
        double bestValue = -Double.MAX_VALUE;
        alphaAMAF params = player.getParameters();

        for (AbstractAction action : children.keySet()) {
            alphaAMAFTreeNode child = children.get(action);
            if (child == null)
                throw new AssertionError("Should not be here");
            else if (bestAction == null)
                bestAction = action;

            boolean iAmMoving = state.getCurrentPlayer() == player.getPlayerID();

            //double childValue = child.totValue / (child.nVisits + params.epsilon);
            double childValue = iAmMoving ? child.totValue / (child.nVisits + params.epsilon)
                    : -child.totValue / (child.nVisits + params.epsilon); // 对手回合取负值

            double opponentWeight = 1.0;//123
            if (!iAmMoving && opponentModel.containsKey(action)) {
                opponentWeight += opponentModel.get(action) / (double) root.fmCallsCount;
            }

            double amafValue = child.amafValues.getOrDefault(action, 0.0) /
                    (child.amafVisits.getOrDefault(action, 0) + params.epsilon);

            double variance = (child.totValueSquared / child.nVisits) - (childValue * childValue);
            variance = Math.max(variance, 0);
            double vSa = variance + Math.sqrt(2 * Math.log(root.fmCallsCount) / (child.nVisits + params.epsilon));

            double explorationTerm = params.K * Math.sqrt(Math.log(this.nVisits + 1) / (child.nVisits + params.epsilon) * Math.min(0.25, vSa));

            double alpha = Math.max(0, (params.amafConstant - child.nVisits) / params.amafConstant);

            //double alpha = 0.9;
            double combinedValue = alpha * amafValue + (1 - alpha) * (childValue + explorationTerm);

            combinedValue *= opponentWeight;//123
            combinedValue += estimateOpponentValue(action); // 增加对手的估计值123

            //boolean iAmMoving = state.getCurrentPlayer() == player.getPlayerID();
            //combinedValue = iAmMoving ? combinedValue : -combinedValue;

            //combinedValue = noise(combinedValue,params.epsilon, player.getRnd().nextDouble());
            //double noiseScale = Math.min(1.0, (double)this.nVisits / 100);
            //combinedValue = noise(combinedValue, params.epsilon * (1 - noiseScale), player.getRnd().nextDouble());

            if (combinedValue > bestValue) {
                bestAction = action;
                bestValue = combinedValue;
            }
        }
        if (bestAction == null)
            throw new AssertionError("We have a null value in UCT : shouldn't really happen!");

        root.fmCallsCount++;
        return bestAction;
    }

    /**
     * Perform a Monte Carlo rollout from this node.
     *
     * @return - value of rollout.
     */
    private double rollOut() {
        //List<AbstractAction> rolloutActions = new ArrayList<>();
        rolloutActions = new ArrayList<>();
        int rolloutDepth = 0;

        AbstractGameState rolloutState = state.copy(); // 复制游戏状态

        if (player.getParameters().rolloutLength > 0) {
            while (!finishRollout(rolloutState, rolloutDepth)) {
                List<AbstractAction> availableActions = randomPlayer.getForwardModel().computeAvailableActions(
                        rolloutState, randomPlayer.parameters.actionSpace);

                AbstractAction next = selectBiasedAction(rolloutState, availableActions); // 使用偏向性选择动作
                advance(rolloutState, next); // 将模拟的状态推进到下一步
                rolloutActions.add(next);
                rolloutDepth++;
            }
        }

        // 评估最终状态并返回标准化得分
        double value = player.getParameters().getHeuristic().evaluateState(rolloutState, player.getPlayerID());
        if (Double.isNaN(value))
            throw new AssertionError("Illegal heuristic value - should be a number");

        //backUp(value, rolloutActions);
        return value;
    }

    /**
     * 根据对手模型的估计值偏向性地选择动作
     */
    private AbstractAction selectBiasedAction(AbstractGameState rolloutState, List<AbstractAction> availableActions) {
        Map<AbstractAction, Double> actionProbabilities = new HashMap<>();
        boolean iAmMoving = rolloutState.getCurrentPlayer() == player.getPlayerID();

        double totalExponentiatedWeight = 0.0;

        // 计算每个动作的指数权重，并求总和
        for (AbstractAction action : availableActions) {
            double weight;

            // 获取对手模型的估计权重
            if (!iAmMoving && opponentModel.containsKey(action)) {
                // 如果是对手回合，根据对手模型的估值偏向性选择
                weight = opponentModel.get(action) / (double) (root.fmCallsCount + 1);
            } else {
                // 否则使用默认权重
                weight = 1.0;
            }

            double exponentiatedWeight = Math.exp(weight);  // 计算指数权重
            actionProbabilities.put(action, exponentiatedWeight);
            totalExponentiatedWeight += exponentiatedWeight;
        }

        // 归一化每个动作的概率
        for (AbstractAction action : actionProbabilities.keySet()) {
            double normalizedProbability = actionProbabilities.get(action) / totalExponentiatedWeight;
            actionProbabilities.put(action, normalizedProbability);
        }

        // 使用加权随机选择算法来选择动作
        double rand = player.getRnd().nextDouble();
        double cumulativeProbability = 0.0;
        for (Map.Entry<AbstractAction, Double> entry : actionProbabilities.entrySet()) {
            cumulativeProbability += entry.getValue();
            if (rand <= cumulativeProbability) {
                return entry.getKey();
            }
        }

        // 默认返回第一个动作，防止没有选中任何动作
        return availableActions.get(0);
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
     //* @param result - value of rollout to backup
     */
    private void backUp(double reward, List<AbstractAction> rolloutActions) {
        // 回溯标准MCTS
        alphaAMAFTreeNode node = this;
        while (node != null) {
            node.nVisits++;
            node.totValue += reward;

            // 回溯AMAF
            for (AbstractAction action : rolloutActions) {
                node.amafVisits.put(action, node.amafVisits.getOrDefault(action, 0) + 1);
                node.amafValues.put(action, node.amafValues.getOrDefault(action, 0.0) + reward);
            }

            node = node.parent;
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
                alphaAMAFTreeNode node = children.get(action);
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

    private double estimateOpponentValue(AbstractAction action) {
        return opponentModel.getOrDefault(action, 0) / (double) root.fmCallsCount;
    }
}

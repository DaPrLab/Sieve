package edu.uci.ics.tippers.model.guard;

import edu.uci.ics.tippers.common.PolicyConstants;
import edu.uci.ics.tippers.db.MySQLQueryManager;
import edu.uci.ics.tippers.db.MySQLResult;
import edu.uci.ics.tippers.model.data.Presence;
import edu.uci.ics.tippers.model.policy.BEExpression;
import edu.uci.ics.tippers.model.policy.BEPolicy;
import edu.uci.ics.tippers.model.policy.BooleanPredicate;
import edu.uci.ics.tippers.model.policy.ObjectCondition;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class FactorSelection {

    //Original expression
    BEExpression expression;

    // Chosen factor/guard
    //TODO: Support multiple object conditions as factor
    List<ObjectCondition> multiplier;

    // Polices from the original expression that contain the factor
    FactorSelection quotient;

    // Polices from the original expression that does not contain the factor
    FactorSelection remainder;

    //Approximate Cost of evaluating the expression
    double cost;

    MySQLQueryManager mySQLQueryManager = new MySQLQueryManager();

    public FactorSelection(BEExpression expression) {
        this.expression = new BEExpression(expression);
        this.multiplier = new ArrayList<ObjectCondition>();
    }

    public BEExpression getExpression() {
        return expression;
    }

    public void setExpression(BEExpression expression) {
        this.expression = expression;
    }

    public List<ObjectCondition> getMultiplier() {
        return multiplier;
    }

    public void setMultiplier(List<ObjectCondition> multiplier) {
        this.multiplier = multiplier;
    }

    public FactorSelection getQuotient() {
        return quotient;
    }

    public void setQuotient(FactorSelection quotient) {
        this.quotient = quotient;
    }

    public FactorSelection getRemainder() {
        return remainder;
    }

    public void setRemainder(FactorSelection remainder) {
        this.remainder = remainder;
    }

    public double getCost() {
        return cost;
    }

    public void setCost(double cost) {
        this.cost = cost;
    }

    public void selectGuards() {
        Set<ObjectCondition> singletonSet = this.expression.getPolicies().stream()
                .flatMap(p -> p.getObject_conditions().stream())
                .filter(o -> PolicyConstants.INDEXED_ATTRS.contains(o.getAttribute()))
                .collect(Collectors.toSet());
        selectFactor(singletonSet);
    }

    /**
     * Factorization based on a single object condition and not all the possible combinations
     * After selecting factor, they are not removed from the quotient
     */
    public void selectFactor(Set<ObjectCondition> objectConditionSet) {
        Boolean factorized = false;
        FactorSelection currentBestFactor = new FactorSelection(this.expression);
        currentBestFactor.setCost(Double.POSITIVE_INFINITY);
        Set<ObjectCondition> removal = new HashSet<>();
        for (ObjectCondition objectCondition : objectConditionSet) {
            BEExpression temp = new BEExpression(this.expression);
            temp.checkAgainstPolices(objectCondition);
            if (temp.getPolicies().size() > 1) { //was able to factorize
                double tCost = temp.estimateCostForSelection();
                double fCost = temp.estimateCostOfGuardRep(objectCondition);
                if (tCost > fCost) {
                    if (currentBestFactor.cost > fCost) {
                        factorized = true;
                        currentBestFactor.multiplier = new ArrayList<>();
                        currentBestFactor.multiplier.add(objectCondition);
                        currentBestFactor.remainder = new FactorSelection(this.expression);
                        currentBestFactor.remainder.expression.getPolicies().removeAll(temp.getPolicies());
                        currentBestFactor.quotient = new FactorSelection(temp);
//                        currentBestFactor.quotient.expression.removeFromPolicies(objectCondition);
                        currentBestFactor.cost = fCost;
                    }
                } else removal.add(objectCondition); //not considered for factorization recursively
            } else removal.add(objectCondition); //not a factor of at least two policies
        }
        if (factorized) {
            this.setMultiplier(currentBestFactor.getMultiplier());
            this.setQuotient(currentBestFactor.getQuotient());
            this.setRemainder(currentBestFactor.getRemainder());
            this.setCost(currentBestFactor.cost);
            removal.add(this.getMultiplier().get(0));
            objectConditionSet.removeAll(removal);
            this.remainder.selectFactor(objectConditionSet);
        }
    }


    public String createQueryFromExactFactor() {
        if (multiplier.isEmpty()) {
            if (expression != null) {
                return this.expression.createQueryFromPolices();
            } else
                return "";
        }
        StringBuilder query = new StringBuilder();
        for (ObjectCondition mul : multiplier) {
            query.append(mul.print());
            query.append(PolicyConstants.CONJUNCTION);
        }
        query.append("(");
        query.append(this.quotient.createQueryFromExactFactor());
        query.append(")");
        if (!this.remainder.expression.getPolicies().isEmpty()) {
            query.append(PolicyConstants.DISJUNCTION);
            query.append("(");
            query.append(this.remainder.createQueryFromExactFactor());
            query.append(")");
        }
        return query.toString();
    }

    /**
     * Estimates the cost of a guarded representation of a set of policies
     * Selectivity of guard * D * Index access + Selectivity of guard * D * cost of filter * alpha * number of predicates
     * alpha is a parameter which determines the number of predicates that are evaluated in the policy (e.g., 2/3)
     *
     * @return
     */
    public double estimateCostOfGuardRep(ObjectCondition guard, BEExpression partition) {
        long numOfPreds = partition.getPolicies().stream().map(BEPolicy::getObject_conditions).mapToInt(List::size).sum();
        return PolicyConstants.NUMBER_OR_TUPLES * guard.computeL() * (PolicyConstants.IO_BLOCK_READ_COST +
                PolicyConstants.ROW_EVALUATE_COST * 2 * numOfPreds * PolicyConstants.NUMBER_OF_PREDICATES_EVALUATED);
    }


    /**
     * returns a map with key as guards and value as the guarded representation of the partition of policies
     * guard is a single object condition
     *
     * @return
     */
    public HashMap<ObjectCondition, BEExpression> getGuardPartitionMapWithRemainder() {
        if (this.getMultiplier().isEmpty()) {
            HashMap<ObjectCondition, BEExpression> remainderMap = new HashMap<>();
            for (BEPolicy bePolicy : this.expression.getPolicies()) {
                double freq = PolicyConstants.NUMBER_OR_TUPLES;
                ObjectCondition gOC = new ObjectCondition();
                for (ObjectCondition oc : bePolicy.getObject_conditions()) {
                    if (!PolicyConstants.INDEXED_ATTRS.contains(oc.getAttribute())) continue;
                    if (oc.computeL() < freq) {
                        freq = oc.computeL();
                        gOC = oc;
                    }
                }
                BEExpression quo = new BEExpression();
                quo.getPolicies().add(bePolicy);
                remainderMap.put(gOC, quo);
            }
            return remainderMap;
        }
        HashMap<ObjectCondition, BEExpression> gMap = new HashMap<>();
        gMap.put(this.getMultiplier().get(0), this.getQuotient().getExpression());
        if (!this.getRemainder().expression.getPolicies().isEmpty()) {
            gMap.putAll(this.getRemainder().getGuardPartitionMapWithRemainder());
        }
        return gMap;
    }

    /**
     * Creates a query by AND'ing the guard and partition and removing all the duplicates in the partition
     *
     * @param guard
     * @param partition
     * @return
     */
    public String createCleanQueryFromGQ(ObjectCondition guard, BEExpression partition) {
        StringBuilder query = new StringBuilder();
        query.append(guard.print());
        query.append(PolicyConstants.CONJUNCTION);
        query.append("(");
        query.append(partition.cleanQueryFromPolices());
        query.append(")");
//        System.out.println(query.toString());
        return query.toString();
    }


    /**
     * Computes the cost of execution of individual guards and sums them up
     * For the remainder it considers the predicate with highest selectivity as the guard and computes the cost
     * Repeats the cost computation *repetitions* number of times and drops highest and lowest value to smooth it out
     * @return total time taken for evaluating guards
     */
    public Duration computeGuardCosts() {
        int repetitions = 5;
        Map<ObjectCondition, BEExpression> gMap = getGuardPartitionMapWithRemainder();
        Duration rcost = Duration.ofMillis(0);
        for (ObjectCondition kOb : gMap.keySet()) {
            List<Long> cList = new ArrayList<>();
            MySQLResult mySQLResult = new MySQLResult();
            for (int i = 0; i < repetitions; i++) {
                mySQLResult = mySQLQueryManager.runTimedQueryWithResultCount(createCleanQueryFromGQ(kOb, gMap.get(kOb)));
                cList.add(mySQLResult.getTimeTaken().toMillis());
            }
            Collections.sort(cList);
            List<Long> clippedList = cList.subList(1, repetitions-1);
            Duration gCost = Duration.ofMillis(clippedList.stream().mapToLong(i -> i).sum() / clippedList.size());
            rcost = rcost.plus(gCost);
        }
        return rcost;
    }

    /**
     * Prints the following to a csv file
     * - For each guard
     * - Number of policies in the partition
     * - Number of predicates in policies
     * - Results returned by the guard
     * - Results returned by the guard + partition
     * - Time taken by each guard
     * - Time taken by each guard + partition
     * - Print the guard + partition
     *
     * @return an arraylist of strings with each element representing a line in the csv file
     */
    public List<String> printDetailedGuardResults() {
        List<String> guardResults = new ArrayList<>();
        Map<ObjectCondition, BEExpression> gMap = getGuardPartitionMapWithRemainder();
        int repetitions = 5;
        Duration totalEval = Duration.ofMillis(0);
        for (ObjectCondition kOb : gMap.keySet()) {
            System.out.println("Executing Guard " + kOb.print());
            StringBuilder guardString = new StringBuilder();
            guardString.append(gMap.get(kOb).getPolicies().size());
            guardString.append(",");
            int numOfPreds = gMap.get(kOb).getPolicies().stream().mapToInt(BEPolicy::countNumberOfPredicates).sum();
            guardString.append(numOfPreds);
            guardString.append(",");
            List<Long> gList = new ArrayList<>();
            List<Long> cList = new ArrayList<>();
            int gCount = 0, tCount = 0;
            for (int i = 0; i < repetitions; i++) {
                MySQLResult guardResult = mySQLQueryManager.runTimedQueryWithResultCount(kOb.print());
                if (gCount == 0) gCount = guardResult.getResultCount();
                gList.add(guardResult.getTimeTaken().toMillis());
                MySQLResult completeResult = mySQLQueryManager.runTimedQueryWithResultCount(createCleanQueryFromGQ(kOb, gMap.get(kOb)));
                if (tCount == 0) tCount = completeResult.getResultCount();
                cList.add(completeResult.getTimeTaken().toMillis());

            }
            Collections.sort(gList);
            List<Long> clippedGList = gList.subList(1, repetitions - 1);
            Duration gCost = Duration.ofMillis(clippedGList.stream().mapToLong(i -> i).sum() / clippedGList.size());
            Collections.sort(cList);
            List<Long> clippedCList = cList.subList(1, repetitions - 1);
            Duration gAndPcost = Duration.ofMillis(clippedCList.stream().mapToLong(i -> i).sum() / clippedCList.size());

            guardString.append(gCount);
            guardString.append(",");
            guardString.append(tCount);
            guardString.append(",");

            guardString.append(gCost.toMillis());
            guardString.append(",");
            guardString.append(gAndPcost.toMillis());
            guardString.append(",");
            guardString.append(createCleanQueryFromGQ(kOb, gMap.get(kOb)));
            guardResults.add(guardString.toString());
            totalEval = totalEval.plus(gAndPcost);
        }
        System.out.println("Total Guard Evaluation time: " + totalEval);
        guardResults.add("Total Guard Evaluation time," + totalEval.toMillis());
        return guardResults;
    }

    public List<ObjectCondition> getIndexFilters() {
        if (multiplier.isEmpty()) {
            return multiplier;
        }
        List<ObjectCondition> indexFilters = new ArrayList<>();
        for (ObjectCondition mul : multiplier) {
            indexFilters.add(mul);
        }
        if (!this.remainder.expression.getPolicies().isEmpty()) {
            indexFilters.addAll(this.remainder.getIndexFilters());
        }
        return indexFilters;
    }


    /**
     * Creates a query by AND'ing the guard and partition
     * @return
     */
    public void createDirtyQuery() {
        for(Map.Entry<ObjectCondition, BEExpression> pair: this.getGuardPartitionMapWithRemainder().entrySet()){
            StringBuilder query = new StringBuilder();
            ObjectCondition guard = pair.getKey();
            BEExpression partition = pair.getValue();
            query.append(guard.print());
            query.append(PolicyConstants.CONJUNCTION);
            query.append("(");
            query.append(partition.createQueryFromPolices());
            query.append(")");
            System.out.println(query.toString());
        }
    }
}
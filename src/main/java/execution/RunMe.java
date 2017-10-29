package execution;

import db.MySQLQueryManager;
import model.policy.BEPolicy;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

/**
 * Created by cygnus on 9/25/17.
 */
public class RunMe {

    public static void main(String args[]){

        List<BEPolicy> policies = BEPolicy.parseJSONList(readFile("/policies/policy1.json"));

        System.out.println(policies.get(0).getObject_conditions().get(0).print());

        System.out.println(policies.get(1).getObject_conditions().get(0).print());

        System.out.println(policies.get(0).getObject_conditions().get(0).checkSame(policies.get(1).getObject_conditions().get(0)));

        System.out.println(policies.get(0).getObject_conditions().get(0).checkOverlap(policies.get(1).getObject_conditions().get(0)));


        MySQLQueryManager mqm = new MySQLQueryManager();
        println(mqm.runCountingQuery("location = 5039"));


//        System.out.println(policies.get(0).getObject_conditions().get(0).sameAs(policies.get(1).getObject_conditions().get(1)));
    }

    public static String readFile(String filename) {
        String result = "";
        try {
            InputStream is = RunMe.class.getResourceAsStream(filename);
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line = br.readLine();
            while (line != null) {
                sb.append(line);
                line = br.readLine();
            }
            result = sb.toString();
        } catch(Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    public static void println(Object line) {
        System.out.println(line);
    }

}
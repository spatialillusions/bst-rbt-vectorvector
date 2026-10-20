import com.greensopinion.vectorvector.ShardBuildKt;
import com.greensopinion.vectorvector.cli.CliOptionsKt;

/** Read-only identity calculation using the exact producer JAR and exact CLI argv. */
public class PrintBuildIdentity {
    public static void main(String[] args) {
        System.out.println(ShardBuildKt.buildIdentity(CliOptionsKt.parseCliOptions(args), args));
    }
}

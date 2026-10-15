import java.sql.*;
import java.util.*;

/** Read-only metadata inspection; never prints manuscript, prompts or API credentials. */
class InspectWritingTasks {
    public static void main(String[] args) throws Exception {
        try(var c=DriverManager.getConnection(Objects.requireNonNull(System.getenv("WRITING_DB_URL")),System.getenv("WRITING_DB_USER"),System.getenv("WRITING_DB_PASSWORD"));
            var s=c.createStatement();var r=s.executeQuery("SELECT t.id,t.status,t.created_at,JSON_EXTRACT(t.payload,'$.modelCalls') calls,JSON_LENGTH(t.payload,'$.snapshot.sources') sources,JSON_UNQUOTE(JSON_EXTRACT(t.payload,'$.error')) error,a.phase,a.status attempt_status,CHAR_LENGTH(a.output_text) output_length,a.usage_json FROM writing_task t LEFT JOIN writing_attempt a ON a.task_id=t.id WHERE t.owner_user_id=1 ORDER BY t.created_at DESC,a.created_at DESC LIMIT 12")) {
            while(r.next())System.out.println("task="+r.getString("id")+" state="+r.getString("status")+" calls="+r.getString("calls")+" sources="+r.getInt("sources")+" phase="+r.getString("phase")+" attempt="+r.getString("attempt_status")+" outputLength="+r.getInt("output_length")+" error="+r.getString("error")+" usage="+r.getString("usage_json"));
        }
    }
}

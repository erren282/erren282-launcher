import org.dsh.servermanager.ServerVersions;
import java.nio.file.Path;
import java.util.List;

public class PaperProbe {
    public static void main(String[] args) throws Exception {
        List<String> versions = ServerVersions.fetchPaperVersions();
        System.out.println("paper versions: " + versions.size() + ", newest: " + versions.get(0));
        String pick = args.length > 0 ? args[0] : versions.get(0);
        // resolve (not download) the latest build by checking the URL through a HEAD-ish request
        java.lang.reflect.Method m = ServerVersions.class.getDeclaredMethod("jsonRequest", String.class);
        m.setAccessible(true);
        Object latest = m.invoke(null, "https://fill.papermc.io/v3/projects/paper/versions/" + pick + "/builds/latest");
        com.google.gson.JsonObject o = (com.google.gson.JsonObject) latest;
        String url = o.getAsJsonObject("downloads").getAsJsonObject("server:default").get("url").getAsString();
        long size = o.getAsJsonObject("downloads").getAsJsonObject("server:default").get("size").getAsLong();
        System.out.println("paper " + pick + " latest build jar: " + size / 1048576 + " MB @ " + url);
        // sanity: the file is reachable (HTTP 200 on first byte via range request)
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setRequestProperty("Range", "bytes=0-0");
        c.setConnectTimeout(8000);
        System.out.println("range request: HTTP " + c.getResponseCode());
    }
}

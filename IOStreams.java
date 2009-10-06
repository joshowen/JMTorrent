import java.io.*;
import java.util.Vector;
import java.util.HashMap;

class IOStreams extends Thread
{
	private BufferedReader i_stream;
	private OutputStream o_stream;
	
	//Vector<HashMap> peer = new Vector<HashMap>();
	
	public IOStreams(BufferedReader in, OutputStream out)
	{
        i_stream = in;
        o_stream = out;
    }
	
	public void run()
	{
		Bencoder bencoder = new Bencoder();
		String line, temp;
		try
		{
			line = i_stream.readLine();
			temp = line;
			while(line != null)
			{
				temp = line;
				System.out.println(line);
				line = i_stream.readLine();
			}
			HashMap response = bencoder.unbencodeDictionary(temp.getBytes());
			System.out.println(response.toString());
			System.out.println(response.get("interval").toString());
			Vector peers = new Vector();
			peers = (Vector)response.get("peers");
			System.out.println(peers.toString());
			
			for(int i = 0; i < peers.size(); i++){
			//	peer.add((HashMap)peers.elementAt(i));
			}
			
			
			
		}
		catch(IOException e){ System.err.println(e); }
	}
}
	
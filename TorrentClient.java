import java.net.*;
import java.io.*;
import java.util.HashMap;
import java.util.StringTokenizer;
import java.util.Random;
import java.util.Vector;

public class TorrentClient
{
	private TorrentFileHandler torrent_file_handler;
	private TorrentFile torrent_file;
	
	private String p_id;
//	private Vector<HashMap> peers = new Vector<HashMap>();
	
	public TorrentClient()
	{
		super();
		torrent_file_handler = new TorrentFileHandler();
	}

	public TorrentClient(String filepath)
	{
		super();
		
		torrent_file_handler = new TorrentFileHandler();
		p_id = Integer.toString(new Random().nextInt());
		GeneratePID();
		OpenTorrent(filepath);
	}

	public void OpenTorrent(String filepath)
	{	
		if ((torrent_file = torrent_file_handler.openTorrentFile(filepath)) == null)
		{
			System.err.print("Error: There was a problem when unencoding the file \"" 
						+ filepath + "\". File may be missing or corrupted.");
			return;
		}
	}
	
	private void GeneratePID()
	{
		byte[] bytes = new byte[20];
		int num = new Random().nextInt();
		ToolKit.intToBigEndianBytes(num, bytes, 16);
		bytes.toString();
		for(int i = p_id.length(); i < 15; i ++)
		{
			p_id = "-".concat(p_id);
		}
		p_id = "jm352".concat(p_id);
	}
	
	public URL GetTrackerURL()
	{
		try{
			return new URL(torrent_file.tracker_url);
		}
		catch(MalformedURLException e){ System.err.println(e); }
		
		return null;
	}
	
	public String GetInfoHash()
	{
		return torrent_file.info_hash_as_url;
	}
	
	public String GetPID()
	{
		return ToolKit.makeHTTPEscaped(p_id);
	}
	
	public String GetSize()
	{
		return Integer.toString(torrent_file.file_length/torrent_file.piece_length);
	}
	
	public void contactTracker(BufferedReader i_stream, OutputStream o_stream)
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
		//	peers = (Vector)response.get("peers");
		//	System.out.println(peers.toString());
			
		//	for(int i = 0; i < peers.size(); i++){
				
		//	}
			
			
			
		}
		catch(IOException e){ System.err.println(e); }
	}
	
	/**
	 * Generates a new TorrentFileHandlerTester object to demonstrate how to use
	 * a TorrentFileHandler object.
	 * 
	 * @param args Not used.
	 */
	public static void main(String[] args)
	{
		String filepath;
		String destination;
		URL tracker_URL;
		Bencoder bencoder = new Bencoder();
		
		if(args.length < 2)
		{
			System.out.println("Too few arguments!\n");
			return;
		}
		else
		{
			filepath = args[0];
			destination = args[1];
		}
		
		TorrentClient torrent = new TorrentClient(filepath);
		tracker_URL = torrent.GetTrackerURL();
		
		try
		{
			BufferedReader i_stream;
			OutputStream o_stream;
			Socket tracker_sock = new Socket(tracker_URL.getHost(), tracker_URL.getPort());
			//Socket tracker_sock = new Socket(tracker_URL.getHost(), 80);

			tracker_sock.setSoTimeout(5000);
			
			System.out.println("Created socket to " + tracker_URL.getHost() + ":" + tracker_URL.getPort()
					+ " using port " + tracker_sock.getLocalPort());
			
			i_stream = new BufferedReader(new InputStreamReader(tracker_sock.getInputStream()));
			o_stream = tracker_sock.getOutputStream();
			
			String http_req = "/announce?info_hash=" + torrent.GetInfoHash() +
				"&peer_id=" + torrent.GetPID() + "&port=" +
				tracker_sock.getLocalPort() + "&uploaded=0&downloaded=0&left=" +
				torrent.GetSize();
			http_req = "GET ".concat(http_req.concat(" HTTP/1.1\r\n\r\n"));
			
			//contactTracker(i_stream, o_stream);
			o_stream.write(http_req.getBytes());
		}
		catch(UnknownHostException e){System.err.println(e);}
		catch(IOException e){System.err.println(e);}					
	}
}

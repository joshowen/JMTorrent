
import java.util.*;
import java.nio.*;
import java.net.*;
import java.io.*;
import java.security.*;
/**
 * 
 * @author jowen
 * @author mvoswinkel
 * 
 * Threaded client to connect to tracker and download a file.
 */
public class ThreadedTrackerClient {
	//Boolean array of chunks downloaded
	boolean[] chunks;
	
	//Vector containing peer HashMaps
	Vector peers = new Vector();
	
	//Peer ID
	private String p_id;
	
	//Helper for torrents
	private TorrentFileHandler torrent_file_handler;
	
	//Object containing useful torrent information
	private TorrentFile torrent_file;
	
	//Path to torrent
	String filepath;
	
	//Destination
	String destination;
	
	//Location of tracker
	URL tracker_URL;
	
	//Total bytes downloaded
	int totDownloaded;
	
	//Total bytes uploaded
	int totUploaded;
	
	//Sockets and stuff to tracker
	BufferedReader i_stream;
	OutputStream o_stream;
	Socket tracker_sock;
	
	/**
	 * Calls  ThreadedTrackerClient, passes Args
	 * @param args String Array, containing path to torrent file, and path to file location.
	 */
	public static void main(String[] args){
		ThreadedTrackerClient c = new ThreadedTrackerClient(args);
	}
	
	
	
	
	/**
	 * Connects to tracker, and unbencodes the hashmap response
	 * @param i_stream InputStram
	 * @param o_stream OutputStream
	 */
	public void contactTracker(BufferedReader i_stream, OutputStream o_stream){
		Bencoder bencoder = new Bencoder();
		String line, temp;
		try
		{
			line = i_stream.readLine();
			temp = line;
			//Read to the last line, skips HTTP header info
			while(line != null)
			{
				temp = line;
				line = i_stream.readLine();
			}
			byte[] hash = new byte[20];
			//Unbencode the tracker response and store as a hashmap
			HashMap response = bencoder.unbencodeDictionary(temp.getBytes());
			//Fill the peers vector with peer hashmaps
			peers = (Vector)response.get("peers");
		}
		catch(IOException e){ System.err.println(e); }
	}

	
	
	/**
	 * Sends event to the tracker
	 * @param os OutputStream to send message on
	 */
	private void SendEventToTracker(OutputStream os,Socket ts,String event){
		//Build the message
		String http_req = "/announce?info_hash=" + torrent_file.info_hash_as_url +
		"&peer_id=" + ToolKit.makeHTTPEscaped(p_id) + "&port=" +
		ts.getLocalPort() + "&uploaded=0&downloaded=0&left=" +
		torrent_file.file_length + "&event=" + event;
		http_req = "GET ".concat(http_req.concat(" HTTP/1.1\r\n\r\n"));
		//Put the message on the wire
		try{
			os.write(http_req.getBytes());
		}catch(Exception e){}
	}
	
	
	/**
	 * Opens TorrentFile, checks that its valid
	 * @param filepath Path to Torrent File.
	 */
	public void OpenTorrent(String filepath)
	{	
		if ((torrent_file = torrent_file_handler.openTorrentFile(filepath)) == null)
		{
			System.err.print("Error: There was a problem when unencoding the file \"" 
						+ filepath + "\". File may be missing or corrupted.");
			return;
		}
	}

	
	
	
	/**
	 * Generates PID 
	 *
	 */
	private void GeneratePID()
	{
		byte[] bytes = new byte[20];
		bytes.toString();
		for(int i = p_id.length(); i < 15; i ++)
		{
			p_id = "-".concat(p_id);
		}
		p_id = "jm352".concat(p_id);
	}

	
	
	
	/**
	 * Returns URL of Tracker
	 * @return Tracker URL
	 */
	public URL GetTrackerURL()
	{
		try{
			return new URL(torrent_file.tracker_url);
		}
		catch(MalformedURLException e){ System.err.println(e); }
		
		return null;
	}

	
	
	/**
	 * Function which parses information out of torrent file, and processes it, thus creating download threads.
	 * @param args String Array containing the Torrent File location and the File location
	 */
	private ThreadedTrackerClient(String[] args){
		
		//Check to make sure there are 2 arguments, requires torrent file and destination file
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
		
		torrent_file_handler = new TorrentFileHandler();
		p_id = Integer.toString(new Random().nextInt());
		GeneratePID();
		OpenTorrent(filepath);
		
		tracker_URL = GetTrackerURL();
		
		chunks = new boolean[torrent_file.file_length/torrent_file.piece_length+1];
		
		BufferedReader i_stream;
		OutputStream o_stream;
		Socket tracker_sock;
		try
		{
			//Connect to tracker and get response
			tracker_sock = new Socket(tracker_URL.getHost(), tracker_URL.getPort());
			
			tracker_sock.setSoTimeout(5000);
			
			
			i_stream = new BufferedReader(new InputStreamReader(tracker_sock.getInputStream()));
			o_stream = tracker_sock.getOutputStream();
			
			String http_req = "/announce?info_hash=" + torrent_file.info_hash_as_url +
				"&peer_id=" + ToolKit.makeHTTPEscaped(p_id) + "&port=" +
				tracker_sock.getLocalPort() + "&uploaded=0&downloaded=0&left=" +
				torrent_file.file_length;
			http_req = "GET ".concat(http_req.concat(" HTTP/1.1\r\n\r\n"));
			

			o_stream.write(http_req.getBytes());
			contactTracker(i_stream, o_stream);
		}
		catch(UnknownHostException e){System.err.println(e);}
		catch(IOException e){System.err.println(e);}		
		
		/*
		 * Find a peer to connect to, currently set to just use the first peer returned
		 * Can easily be extended to download from multiple peers by removing the i<1.
		 */
		for(int i=0;i<peers.size() && i<1;i++){
			//Pull the peer HashMap
			HashMap p = (HashMap)peers.get(i);
			//Create a new downloader with the peer
			Downloader dl = new Downloader(p);
			//Start 'er up
			dl.start();
		}
	}
	
	
	
	
	/**
	 * 
	 * @author jowen
	 * @author mvoswinkel
	 * 
	 * Thread that handles downloading of files
	 */
	class Downloader extends Thread{
	    boolean am_choking = true;
	    boolean am_interested = false;
	    boolean peer_choking = true;
	    boolean peer_interested = false;
	    //Outgoing Message Queue for this thread
	    LinkedList<byte[]> outQueue = new LinkedList();
	    //Incoming Message Queue for this thread
	    LinkedList<Message> inQueue = new LinkedList();
	    /*
	     * Set the initial chunk to 0, so it will start from the beginning and try
	     * to find the next undownloaded chunk sequentially
	    */
	    int chunk=0;
	    Socket s;
	    /*
	     * Used Data**putStreams so we could use .readInt,.writeByte,etc after we 
	     * discovered Java is BigEndian
	     */
	    DataOutputStream os;
		DataInputStream is;
		//Make the downloader easy to reference from children
		Downloader down = this;
		//Create listener,sender, and keepalive threads
		Listener list = new Listener();
		Sender send = new Sender();
		KeepAlive keep = new KeepAlive();

		/*
		 * File pointer to the file we will write to.  We used random access file
		 * so we can write anywhere in the file.  Great for concurrent downloads 
		 * from different peers.
		 */
		RandomAccessFile file;
		
		
		
		/**
		 * Announces self to other peer, gets response
		 * @param pstrlen Length of Protocol Header
		 * @param pstr Protcol Header
		 * @param info_hash InfoHash from Torrent File
		 * @param peer_id PeerId (Random)
		 */
		public void handshake(int pstrlen, String pstr, byte[] info_hash, String peer_id){
			 //Create a ByteBuffer the size of the handshake
			 ByteBuffer bb = ByteBuffer.allocate(49+pstrlen);
			 byte[] byteArr = new byte[8];
			 byte by = (byte)pstrlen;
			 try{
				 //Build the handshake using a ByteBuffer
				 bb.put(by);
				 bb.put(pstr.getBytes());
				 bb.put(byteArr);
				 bb.put(info_hash);
				 bb.put(peer_id.getBytes());
				 //Send out the handshake
				 os.write(bb.array());
				 int size = is.read();
				 byte[] handshakeResponse = new byte[48+size];
				 //Read in the handshake
				 is.readFully(handshakeResponse);
				 //TODO Parse the handshake response
			}catch(Exception e){e.printStackTrace();}
		}
		
		
		
		/**
		 * Creates a message requeiting part of a chunk.
		 * @param piece Integer specifying the chunk being requested
		 * @param begin Integer specifying the offset
		 */
		public void requestBlock(int piece,int begin,int size){
			int[] payload = {piece, begin, size};
			sendMsg(13, 6, payload); // send request for block;
		}
		
		
		/**
		 * Creates a message, and adds it to the outgoing Message Queue, for the Sender thread to send.
		 * @param prefix Mesage Prefix
		 * @param msgID Message ID
		 * @param payload Message Payload (If none, then null)
		 */
		public void sendMsg(int prefix, int msgID, int[] payload){
			int off=0;
			//Create byte[] for the size of message
			byte[] b = new byte[4+prefix];
			//Add the first 4 bytes (msg size)
			ToolKit.intToBigEndianBytes(prefix,b,0);
			off=off+4;
			//Add the message ID
			b[4]=(byte)msgID;
			off++;
			/*
			 * If there is a payload (Anything other than size and message id) add it
			 * as BigEndian bytes
			 */
			if(payload != null){ 
				for(int i = 0; i < payload.length; i++){
					ToolKit.intToBigEndianBytes(payload[i],b,off);
					off=off+4;
				}
			}

			try{
				//Throw it in the outQueu and notify anything waiting on the outQueue
				synchronized(outQueue){
					outQueue.add(b);
					outQueue.notify();
				}
			}
			catch(Exception e){e.printStackTrace();}
		}
		
		
		
		/**
		 * Thread that does the downloading of the file.  It waits for messages on the inputQueue and processes them.
		 * @param p HashMap with peer information
		 */
		Downloader(HashMap p){
			//int offset=0;
			try{
				//Create a socket and input+output streams
				s = new Socket((String)p.get("ip"),Integer.parseInt(p.get("port").toString()));
				os = new DataOutputStream(s.getOutputStream());
				is = new DataInputStream(s.getInputStream());
				//Connect to the file with rw privleges.  If the file doesnt exist it will crete one
				file = new RandomAccessFile(destination,"rw");
				file.setLength(torrent_file.file_length);
				
				//Shake hands with the peer
				handshake(19, "BitTorrent protocol", torrent_file.info_hash_as_binary,
						 p_id);
				
				//Start the send,keepalive, and listen threads
				send.start();
				keep.start();
				list.start();

				while(true){
					//Find another available chunk to download
					chunk=0;
					boolean found=false;
					synchronized(chunks){
						for(;chunk<chunks.length;chunk++){
							if(chunks[chunk]==false){
								found=true;
								break;
							}
						}
					}
					if(found==false){
						break;//Download complete, check hash
					}else{
						//More to download!
						boolean moreToDL = true;
						
						//Create a byte array that is the size of the piece to download
						byte[] bytes;
						
						//Make sure the chunk size is bigger than the file size. If not then
						//make the byte array size the filesize
						if(torrent_file.file_length<torrent_file.piece_length){
							bytes = new byte[torrent_file.file_length];
						}else{
							bytes = new byte[torrent_file.piece_length];
						}
						
						while(moreToDL){
							Message m;
							synchronized(inQueue){
								if(inQueue.isEmpty())inQueue.wait();
								m = inQueue.remove();
							}
							
							switch(m.messageType){
							case -1:
								//Other
								//System.exit(0);
								break;
							case 0:
								//choke
								am_choking = true;
								break;
							case 1:
								 //unchoke
								 am_choking = false;
								 
								 int reqSize = 16384;
								 //Check to see if the piece length is less than 2^14, set reqSize to piece length if true.
								 if(torrent_file.piece_length<reqSize)reqSize = torrent_file.piece_length;
								 //Check to see if the file length is less than 2^14, set reqSize to file length if true.
								 if(torrent_file.file_length<reqSize)reqSize = torrent_file.file_length;
								 requestBlock(chunk, 0,reqSize);
								 break;
							case 2:
								 //interested
								 am_interested = true;
								 break;
							case 3:
								 //not interested
								am_interested = false;
								 break;
							case 4:
								 //have
								break;
							case 5:
								 //bitfield
								sendMsg(1, 2, null); //send interested message;
								break;
							case 6:
								//request
								
								break;
							case 7:
								//Piece
								//Find the offset of the piece in the array
								int fileOffset = m.block*torrent_file.piece_length + m.offset;
								
								//Copy the message to the chunks byte array
								System.arraycopy(m.message, 0, bytes, m.offset, m.message.length);
								
								//Increase the offset
								int offset = m.offset + m.message.length;
								
								//Check if there is more file to download, if not, exit loop
								if(offset >= torrent_file.piece_length || fileOffset>=torrent_file.file_length){
									moreToDL=false;
									break;
								}
								
								//Set the next request size
								int reqSize2 = 16384;
								
								//Check to make sure there is at least that much to download in the chunk
								//If not then change the request size to be the remainign ammt in the chunk
								if(torrent_file.file_length-offset<16384)reqSize2 =torrent_file.file_length-offset;
								
								//Request the new block of the chunk
								requestBlock(chunk,offset,reqSize2);
								break;
							case 8:
								//Cancel
							case 9:
								//Port
							}
							
						}
						//Check chunk's hash
						//Generate a hash of the chunk downloaded
						byte[] hash = new byte[20];
						MessageDigest sha = MessageDigest.getInstance("SHA-1");
						hash = sha.digest(bytes);
						//Compare the hash generated with the hash that the tracker gave us
						if(Arrays.equals(hash, (byte[])torrent_file.piece_hash_values_as_binary.get(chunk))){
							//The Hash Matches, the chunk is downloaded, write to disk
							synchronized(file){
								file.seek(0);
								file.skipBytes(chunk*torrent_file.piece_length);
								file.write(bytes);
								totDownloaded = totDownloaded + bytes.length;
							}
							chunks[chunk]=true;
						}else{
							//The hash did not match, try downloading it again
							System.out.println("The hash did not match, trying again!");
						}
					}
				}
				
				//Chunk download complete
				
				//Alert the user to the successful download
				System.out.println("Download successful!");
				
				//Send the completed message to the tracker
				SendEventToTracker(o_stream,s,"Completed");
				//End the threads
				list.interrupt();
				keep.interrupt();
				send.interrupt();
				
				//Exit the system
				System.exit(1);
			}catch(Exception e){e.printStackTrace();}
		}
		
		
		
		
		/**
		 * 
		 * @author jowen
		 * @author mvoswinkel
		 *
		 * Class listens for messages on the DataInputStream.  When incoming message is received, a Message object is created
		 * and its fields are filled.  If there is an additional payload other than the messageType, it is input.
		 */
		class Listener extends Thread{
			/**
			 * Function that listens for messages from DataInputStream and adds them to Queue
			 */
			public void run(){
				try{
					while(true){
						//Get message size
						byte[] len = new byte[4];
						is.read(len);
						int size = ToolKit.bigEndianBytesToInt(len, 0);
						
						if(size>0){//Size 0 is a keepalive
							Message m = new Message();
							//Get message type
							m.messageType = is.read();
							
							if(m.messageType>3){//1-3 dont have an additional payload
								//Read the rest of the message
								if(m.messageType!=7){
									m.message = new byte[size-1];
									is.readFully(m.message);
								}else{
									m.block=is.readInt();
									m.offset=is.readInt();
									m.message=new byte[size-9];
									is.readFully(m.message);
								}
							}
							synchronized(inQueue){
								//Add message to the incoming queue, and notify anyone waiting on it
								inQueue.add(m);
								inQueue.notify();
							}
							
						}else{
							System.out.println();
						}
					}
				}catch(Exception e){}
			}
		}
		
		
		
		
		/**
		 * 
		 * @author jowen
		 * @author mvoswinkel
		 * Thread Sends KeepAlive messages to the other peer every 30 seconds
		 */
		class KeepAlive extends Thread{
			/**
			 * Actual method that adds messages to the Queue, notifies it, then sleeps.
			 */
			public void run(){
				while(true){
					//Generate a 4 byte array of 0s which is a keep alive message
					byte[] b = new byte[4];
					//Add the message to the outgoing Queue and notify it
					synchronized(outQueue){
						outQueue.add(b);
						outQueue.notify();
					}
					try{
						//Wait for 30 seconds and repeat
						sleep(30000);
					}catch(Exception e){}
				}
			}
		}
		
		
		
		
		/**
		 * 
		 * @author jowen
		 * @author mvoswinkel
		 * 
		 * Thread sends messages to peer.  It sleeps until it is notified that there is something in the Queue, sends it, and sleeps again.
		 * 
		 */
		class Sender extends Thread{
			/**
			 * Function sends messages to output stream when they are in the Queue
			 */
			public void run(){
				try{
					while(true){
						byte[] b;
						//If there is nothing in the queue, block until there is something
						//and am notified.  Otherwise remove it and set it to the byte[] b
						synchronized(outQueue){
							if(outQueue.isEmpty())outQueue.wait();
							b = outQueue.remove();
						}
						//Write b to the output stream and flush, then repeat
						os.write(b);
						os.flush();
					}
				}catch(Exception e){}
			}			
		}
	}
}

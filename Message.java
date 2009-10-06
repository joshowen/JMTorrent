/**
 * Class used to keep parsed messages together.
 * 
 * @author jowen
 * @author mvoswinkel
 */
public class Message {
	//Message, also refered to as payload, any bytes after the messageID
	public byte[] message;
	//Message ID
	public int messageType;
	//Piece length, used for piece requests
	public int length;
	//Piece offset, used for pieces
	public int offset;
	//Piece chunk, used or pieces
	public int block;
}

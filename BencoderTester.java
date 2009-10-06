/*
 * Copyright 2006 Robert Sterling Moore II
 * 
 * This computer program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any
 * later version.
 * 
 * This computer program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this computer program; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Vector;

public class BencoderTester
{
	public BencoderTester()
	{
		super();

		Bencoder bencoder = new Bencoder();
		
		/*
		 * Strings
		 */
		byte[] encoded = bencoder.bencodeString("Hello World!\n12345\t234");

		System.out.print("Bencoded String: ");
		for (int i = 0; i < encoded.length; i++)
		{
			System.out.print((char) encoded[i]);
		}
		System.out.println();

		System.out.println("Unbencoded String: "
				+ bencoder.unbencodeString(encoded));

		
		/*
		 * Integer
		 */
		encoded = bencoder.bencodeInteger(new Integer(-123456));

		System.out.print("Bencoded Integer: ");
		for (int i = 0; i < encoded.length; i++)
		{
			System.out.print((char) encoded[i]);
		}
		System.out.println();

		System.out.println("Unbencoded Integer: "
				+ bencoder.unbencodeInteger(encoded).intValue());

		
		/*
		 * Dictionary
		 */
		HashMap hash_map = new HashMap();
		hash_map.put(bencoder.bencodeString("Hello"), bencoder
				.bencodeString("Goodbye"));
		hash_map.put(bencoder.bencodeString("Another"), bencoder
				.bencodeInteger(new Integer(234)));

		encoded = bencoder.bencodeDictionary(hash_map);

		System.out.print("Bencoded Dictionary: ");
		for (int i = 0; i < encoded.length; i++)
		{
			System.out.print((char) encoded[i]);
		}
		System.out.println();

		System.out.println("Unbencoded Dictionary:");
		hash_map = bencoder.unbencodeDictionary(encoded);
		
		Iterator it = hash_map.entrySet().iterator();

		while (it.hasNext())
		{
			Map.Entry me = (Map.Entry) it.next();
			String key = me.getKey().toString();
			String value = me.getValue().toString();
			System.out.println("Key: " + key + " Value: " + value);
		}
		
		
		/*
		 * List
		 */
		Vector list1 = new Vector();
		list1.add(bencoder.bencodeString("Hello!"));
		list1.add(bencoder.bencodeInteger(new Integer(-345)));
		list1.add(bencoder.bencodeString("Hiya!"));
		list1.add(encoded);
		
		encoded = bencoder.bencodeList(list1);
		
		System.out.print("Bencoded List: ");
		for(int i = 0; i < encoded.length; i++)
		{
			System.out.print((char)encoded[i]);
		}
		System.out.println();		
		
		list1 = bencoder.unbencodeList(encoded);
		System.out.print("Unbencoded List: " + list1);
		
	}

	public static void main(String[] args)
	{
		BencoderTester bt = new BencoderTester();
	}
}

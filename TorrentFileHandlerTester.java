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
//package main;

/**
 * A class used as an example of how to use the TorrentFileHandler class.
 * 
 * @author Robert S. Moore II
 */
public class TorrentFileHandlerTester
{
	private TorrentFileHandler torrent_file_handler;

	private TorrentFile torrent_file;

	/**
	 * Invokes a private method to load a specific .torrent file, parse it, and
	 * display its unencoded contents.
	 * 
	 */
	public TorrentFileHandlerTester()
	{
		super();

		testTorrentFileHandler();
	}

	/*
	 * Precondition: None Postcondition: If "Kinkakuji - Main Temple
	 * 3.JPG.torrent" exists in the current directory, it unencodes the data and
	 * extracts the fields necessary for the first project.
	 */
	private void testTorrentFileHandler()
	{
		torrent_file_handler = new TorrentFileHandler();
		torrent_file = torrent_file_handler
				.openTorrentFile("Rutgers_Fight_Song.mp3.torrent");

		if (torrent_file != null)
		{

			System.out.println("Tracker URL: " + torrent_file.tracker_url);
			System.out
					.println("File Size (Bytes): " + torrent_file.file_length);
			System.out.println("Piece Size (Bytes): "
					+ torrent_file.piece_length);
			System.out.println("SHA-1 Info Hash: "
					+ torrent_file.info_hash_as_url);
			for (int i = 0; i < torrent_file.piece_hash_values_as_hex.size(); i++)
			{
				System.out.println("SHA-1 Hash for Piece ["
						+ i
						+ "]: "
						+ (String) torrent_file.piece_hash_values_as_url
								.elementAt(i));
			}
		}
		else
		{
			System.err.println("Error: There was a problem when unencoding the file \"Kinkakuji - Main Temple 3.JPG.torrent\".");
			System.err.println("\t\tPerhaps it does not exist.");
		}

	}

	/**
	 * Generates a new TorrentFileHandlerTester object to demonstrate how to use
	 * a TorrentFileHandler object.
	 * 
	 * @param args Not used.
	 */
	public static void main(String[] args)
	{
		TorrentFileHandlerTester tfht = new TorrentFileHandlerTester();
	}
}

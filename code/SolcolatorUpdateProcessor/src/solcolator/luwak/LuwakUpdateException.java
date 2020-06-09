package solcolator.luwak;

import java.util.List;
import java.io.IOException;

public class LuwakUpdateException {
	public static String getPrintableErrorString(List<IOException> errors) {
		StringBuilder printableString = new StringBuilder("\r\n");
		
		for (IOException error : errors) {
			printableString.append("\r\nError: ");
			printableString.append(error.getMessage());
			printableString.append("\r\n");
		}

		return printableString.toString();
	}
}

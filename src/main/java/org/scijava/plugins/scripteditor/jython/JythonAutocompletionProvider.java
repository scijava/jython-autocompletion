/*-
 * #%L
 * Jython language support for SciJava Script Editor.
 * %%
 * Copyright (C) 2020 - 2022 SciJava developers.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package org.scijava.plugins.scripteditor.jython;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;

import org.fife.ui.autocomplete.BasicCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.scijava.ui.swing.script.autocompletion.ClassUtil;
import org.scijava.ui.swing.script.autocompletion.ImportCompletionImpl;
import org.scijava.ui.swing.script.autocompletion.ImportFormat;

public class JythonAutocompletionProvider extends DefaultCompletionProvider {
	
	private final JythonAutoCompletions autoCompletions = new JythonAutoCompletions();
	private final RSyntaxTextArea text_area;
	private final ImportFormat formatter;
	
	public JythonAutocompletionProvider(final RSyntaxTextArea text_area, final ImportFormat formatter) {
		this.text_area = text_area;
		this.formatter = formatter;
		this.setParameterizedCompletionParams('(', ", ", ')'); // for methods and functions
		new Thread(ClassUtil::ensureCache).start();
	}
	
	/**
	 * Override parent implementation to allow letters, digits, the period and a space, to be able to match e.g.:
	 * 
	 * "from "
	 * "from ij"
	 * "from ij.Im"
	 * etc.
	 * 
	 * @param c
	 */
	@Override
	public boolean isValidChar(final char c) {
		return Character.isLetterOrDigit(c) || '.' == c || ' ' == c;
	}
	
	@SuppressWarnings("unused")
	static private final Pattern
			fromImport = Pattern.compile("^((from|import)[ \\t]+)([a-zA-Z_][a-zA-Z0-9._]*)$"),
			fastImport = Pattern.compile("^(from[ \\t]+)([a-zA-Z_][a-zA-Z0-9._]*)[ \\t]+$"),
			importStatement = Pattern.compile("^((from[ \\t]+([a-zA-Z0-9._]+)[ \\t]+|[ \\t]*)import[ \\t]+)([a-zA-Z0-9_., \\t]*)$"),
			simpleClassName = Pattern.compile("^(.*[ \\t]+|)([A-Z_][a-zA-Z0-9_]+)$"),
			staticMethodOrField = Pattern.compile("^((.*[ \\t]+|)([A-Z_][a-zA-Z0-9_]*)\\.)([a-zA-Z0-9_]*)$");
	
	private final List<Completion> asCompletionList(final Stream<String> stream, final String pre) {
		return stream
				.map((s) -> new BasicCompletion(JythonAutocompletionProvider.this, pre + s))
				.collect(Collectors.toList());
	}
	
	@Override
	public List<Completion> getCompletionsImpl(final JTextComponent comp) {
		final ArrayList<Completion> completions = new ArrayList<>();
		String currentLine,
			   codeWithoutLastLine,
			   alreadyEnteredText = this.getAlreadyEnteredText(comp);
		try {
			codeWithoutLastLine = comp.getText(0, comp.getCaretPosition());
			final int lastLineBreak = codeWithoutLastLine.lastIndexOf("\n") + 1;
			currentLine = codeWithoutLastLine.substring(lastLineBreak); // up to the caret position
			codeWithoutLastLine = codeWithoutLastLine.substring(0, lastLineBreak);
		} catch (BadLocationException e1) {
			e1.printStackTrace();
			return completions;
		}
		// Completions provided by listeners (e.g. for methods and fields and variables and builtins from jython-autocompletion package)
		try {
			final List<Completion> cs = autoCompletions.completionsFor(this, codeWithoutLastLine, currentLine, alreadyEnteredText);
			if (cs != null) completions.addAll(cs);
		}
		catch (Exception e) {
			JythonDev.print("Failed to get autocompletions from " + autoCompletions);
			JythonDev.printError(e);
		}
		// Java class discovery for completions with auto-imports
		completions.addAll(getCompletions(alreadyEnteredText));
		return completions;
	}

	/** Completions to discover (autocomplete imports) and auto-import java classes. */
	public List<Completion> getCompletions(final String text) {
		// don't block
		if (!ClassUtil.isCacheReady()) return Collections.emptyList();

		// E.g. "from ij" to expand to a package name and class like ij or ij.gui or ij.plugin
		final Matcher m1 = fromImport.matcher(text);
		if (m1.find())
			return asCompletionList(ClassUtil.findClassNamesContaining(m1.group(3)).map(formatter::singleToImportStatement), "");

		final Matcher m1f = fastImport.matcher(text);
		if (m1f.find())
			return asCompletionList(ClassUtil.findClassNamesForPackage(m1f.group(2)).map(formatter::singleToImportStatement), "");

		// E.g. "from ij.gui import Roi, Po" to expand to PolygonRoi, PointRoi for Jython
		final Matcher m2 = importStatement.matcher(text);
		if (m2.find()) {
			final String packageName = m2.group(3);
			String className = m2.group(4); // incomplete or empty, or multiple separated by commas with the last one incomplete or empty

			JythonDev.print("m2 matches className: " + className);
			final String[] bycomma = className.split(",");
			String precomma = "";
			if (bycomma.length > 1) {
				className = bycomma[bycomma.length -1].trim(); // last one
				for (int i=0; i<bycomma.length -1; ++i)
					precomma += bycomma[0] + ", ";
			}
			Stream<String> stream;
			if (className.length() > 0)
				stream = ClassUtil.findClassNamesStartingWith(null == packageName ? className : packageName + "." + className);
			else
				stream = ClassUtil.findClassNamesForPackage(packageName);
			// Simple class names
			stream = stream.map((s) -> s.substring(Math.max(0, s.lastIndexOf('.') + 1)));
			return asCompletionList(stream, m2.group(1) + precomma);
		}
		
		final Matcher m3 = simpleClassName.matcher(text);
		if (m3.find()) {
			// Side effect: insert the import at the top of the file if necessary
			//return asCompletionList(ClassUtil.findSimpleClassNamesStartingWith(m3.group(2)).stream(), m3.group(1));
			return ClassUtil.findSimpleClassNamesStartingWith(m3.group(2)).stream()
					.map(className -> new ImportCompletionImpl(JythonAutocompletionProvider.this,
							m3.group(1) + className.substring(className.lastIndexOf('.') + 1),
							className,
							formatter.singleToImportStatement(className)))
					.collect(Collectors.toList());
		}
		

		/* Covered by listener from jython-completions

		final Matcher m4 = staticMethodOrField.matcher(text);
		try {

			String simpleClassName;
			String methodOrFieldSeed;
			String pre;
			boolean isStatic;

			if (m4.find()) {

				// a call to a static class
				pre = m4.group(1);
				simpleClassName   = m4.group(3); // expected complete, e.g. ImagePlus
				methodOrFieldSeed = m4.group(4).toLowerCase(); // incomplete: e.g. "GR", a string to search for in the class declared fields or methods
				isStatic = true;

			} else {

				// a call to an instantiated class
				final String[] varAndSeed = getVariableAnSeedAtCaretLocation();
				if (varAndSeed == null) return Collections.emptyList();

				simpleClassName = JythonAutoCompletion.findClassAliasOfVariable(varAndSeed[0], text_area.getText());
				if (simpleClassName == null) return Collections.emptyList();

				pre = varAndSeed[0] + ".";
				methodOrFieldSeed = varAndSeed[1];
				isStatic = false;

//				System.out.println("simpleClassName: " + simpleClassName);
//				System.out.println("methodOrFieldSeed: " + methodOrFieldSeed);

			}

			// Retrieve all methods and fields, if the seed is empty
			final boolean includeAll = methodOrFieldSeed.trim().isEmpty();

			// Scan the script, parse the imports, find first one matching
			final Import im = JythonAutoCompletion.findImportedClasses(text_area.getText()).get(simpleClassName);
			if (null != im) {
				try {
					final Class<?> c = Class.forName(im.className);
					final ArrayList<Completion> completions = new ArrayList<>();
					for (final Field f: c.getFields()) {
						if (isStatic == Modifier.isStatic(f.getModifiers()) &&
								(includeAll || f.getName().toLowerCase().contains(methodOrFieldSeed)))
							completions.add(ClassUtil.getCompletion(this, pre, f, c));
					}
					for (final Method m: c.getMethods()) {
						if (isStatic == Modifier.isStatic(m.getModifiers()) &&
								(includeAll || m.getName().toLowerCase().contains(methodOrFieldSeed)))
						completions.add(ClassUtil.getCompletion(this, pre, m, c));
					}

					Collections.sort(completions, new Comparator<Completion>() {
						int prefix1Index = Integer.MAX_VALUE;
						int prefix2Index = Integer.MAX_VALUE;
						@Override
						public int compare(final Completion o1, final Completion o2) {
							prefix1Index = Integer.MAX_VALUE;
							prefix2Index = Integer.MAX_VALUE;
							if (o1.getReplacementText().startsWith(pre))
								prefix1Index = 0;
							if (o2.getReplacementText().startsWith(pre))
								prefix2Index = 0;
							if (prefix1Index == prefix2Index)
								return o1.compareTo(o2);
							else
								return prefix1Index - prefix2Index;
						}
					});

					return completions;
				} catch (final ClassNotFoundException ignored) {
					return ClassUtil.classUnavailableCompletions(this, simpleClassName + ".");
				}
			}
		} catch (final Exception e) {
			e.printStackTrace();
		}
		
		*/
		
		return Collections.emptyList();
	}

	@SuppressWarnings("unused")
	private String[] getVariableAnSeedAtCaretLocation() {
		try {
			final int caretOffset = text_area.getCaretPosition();
			final int lineNumber = text_area.getLineOfOffset(caretOffset);
			final int startOffset = text_area.getLineStartOffset(lineNumber);
			final String lineUpToCaret = text_area.getText(startOffset, caretOffset - startOffset);
			final String[] words = lineUpToCaret.split("\\s+");
			final String[] varAndSeed = words[words.length - 1].split("\\.");
			return (varAndSeed.length == 2) ? varAndSeed : new String[] { varAndSeed[varAndSeed.length - 1], "" };
		} catch (final BadLocationException e) {
			JythonDev.printError(e);
		}
		return null;
	}

}

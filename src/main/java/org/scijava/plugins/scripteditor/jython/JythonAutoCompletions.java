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

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.fife.ui.autocomplete.BasicCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.CompletionProvider;
import org.python.indexer.types.NModuleType;

public class JythonAutoCompletions {
	
	static private final Pattern assign = Pattern.compile("^([ \\t]*)(([a-zA-Z_][a-zA-Z0-9_ \\t,]*)[ \\t]+=[ \\t]+(.*))$"),
						         nameToken = Pattern.compile("^(.*?[ \\t]+|)([a-zA-Z_][a-zA-Z0-9_]+)$"),
						         dotNameToken = Pattern.compile("^(.*?[ \\t]+|)([a-zA-Z0-9_\\.\\[\\](){}]+)\\.([a-zA-Z0-9_]*)$"),
						     	 endingCode = Pattern.compile("^([ \\t]*)[^#]*?(.*?)[ \\t]*:[ \\t]*(#.*|)[\\n]*$"),
						     	 sysPathAppend = Pattern.compile("sys.path.append[ \\t]*[(][ \\t]*['\"](.*?)['\"][ \\t]*[)]"), // fragile to line breaks in e.g. .append
						    	 importPkg = Pattern.compile("^(import|from)[ \\t]+([a-zA-Z_][a-zA-Z0-9._]*)$"),
								 importMember = Pattern.compile("^from[ \\t]+([a-z_][a-zA-Z0-9_.]*)[ \\t]+import[ \\t]*([a-zA-Z0-9_]*)$");

	static public final List<String> jython_jar_modules;
	
	static {
		List<String> ls = Collections.emptyList();
		try {
			ls = Files.walk(new File(System.getProperty("ij.dir") + "/jars/").toPath())
				.filter(path -> path.toFile().getName().startsWith("jython-slim-")) // path.getFileName() doesn't start with ... but prints as if it does ???
				.map(new Function<Path, List<String>>() {
					@Override
					public List<String> apply(final Path filepath) {
						JarFile jar = null;
						try {
							jar = new JarFile(filepath.toString());
							final List<String> modules = jar.stream()
									.map(JarEntry::getName)
									.filter(s -> s.startsWith("Lib/") && s.endsWith(".py"))
									.map(s -> (s.endsWith("/__init__.py") ?
											  s.substring(4, s.length() - 12) // the parent folder
											: s.substring(4, s.length() - 3)) // avoid the .py extension
											.replace('/', '.'))
									.collect(Collectors.toList());
							return modules;
						} catch (Exception e) {
							System.out.println("Failed to load jython module");
							if (JythonAutocompletionProvider.debug >= 2) e.printStackTrace();
						} finally {
							if (null != jar) try { jar.close(); } catch (Exception ee) {}
						}
						return Collections.emptyList();
					}
				}).findFirst().orElse(Collections.emptyList());
		} catch (IOException e) {
			System.out.println("Cannot find jython-slim-*.jar file");
			if (JythonAutocompletionProvider.debug >= 2) e.printStackTrace();
		} finally {
			jython_jar_modules = ls;
		}
	}
	
	
	public JythonAutoCompletions() {}

	public List<Completion> completionsFor(final CompletionProvider provider, String codeWithoutLastLine, final String lastLine, final String alreadyEnteredText) {
		
		// Replacing of text will start at crop, given the already entered text that is considered for replacement
		final int crop = lastLine.length() - alreadyEnteredText.length();
		
		// Query the lastLine to find out what needs autocompletion
		
		// Preconditions 1: can't expand when ending with any of: "()[]{},; "
		final char lastChar = lastLine.charAt(lastLine.length() -1);
		if (0 == lastLine.length() || "()[]{},; ".indexOf(lastChar) > -1)
			return Collections.emptyList();
		
		// Preconditions 2: codeWithoutLastLine has to be valid
		// Analyze last line of codeWithoutLastLine: if it ends with a ':', must add a "pass" to make it a valid code block
		// so that the ParserFacade can work
		boolean add_pass = false;
		if (codeWithoutLastLine.endsWith("\n")) {
			JythonScriptParser.print("codeWithoutLastLine ends with line break");
			final int priorLineBreak = codeWithoutLastLine.lastIndexOf('\n', codeWithoutLastLine.length() - 2);
			final String endingLine = codeWithoutLastLine.substring(priorLineBreak + 1);
			final Matcher me = endingCode.matcher(endingLine);
			if (me.find()) {
				codeWithoutLastLine = codeWithoutLastLine.substring(0, priorLineBreak + 1) + me.group(1) + me.group(2);
				add_pass = true;
				JythonScriptParser.print("changed code to: \n" + codeWithoutLastLine + "\n###");
			}
		}
		
		// Check if there are any additions to the sys.path to search for custom modules
		try {
			final Matcher mpath = sysPathAppend.matcher(codeWithoutLastLine);
			while (mpath.find()) {
				final File path = new File(mpath.group(1));
				if (path.exists() && path.isDirectory() && !Scope.indexer.getLoadPath().stream().filter(s -> path.equals(new File(s))).findFirst().isPresent())
					Scope.indexer.addPath(path.getAbsolutePath());
			}
			JythonScriptParser.print("PYTHONPATH:\n" + String.join("\n", Scope.indexer.getLoadPath()));
		} catch (Exception e) {
			System.out.println("Failed to add path from sys.path.append expression.");
			if (JythonAutocompletionProvider.debug >= 2) e.printStackTrace();
		}
		
		// Situations to autocomplete:
		// 0) a python module import
		// 1) a plain name: delimited with space (or none) to the left, and without parentheses.
		// 2) a method or field: none or some text after a period.
		
		final Matcher mi = importPkg.matcher(lastLine);
		if (mi.find()) {
			// Complete package name
			final String first = mi.group(1), // import or from
			             pkgName = mi.group(2);
			final ArrayList<Completion> ac = new ArrayList<>();
			// Find completions among jython's standard library
			ac.addAll(jython_jar_modules.stream()
					.filter(s -> s.startsWith(pkgName))
					.map(s -> new BasicCompletion(provider, first + " " + s + (first.equals("from") ? " import " : ""), null, "Python standard library module"))
					.collect(Collectors.toList()));
			// Find completions among sys.path libraries
			final String pkgNameFile = pkgName.replace('.', '/');
			ac.addAll(Scope.indexer.getLoadPath().stream()
					.map(dir -> {
						try {
							return Files.walk(new File(dir).toPath(), FileVisitOption.FOLLOW_LINKS)
									.map(path -> path.toFile().getAbsolutePath())
									.filter(s -> s.startsWith(dir + pkgNameFile) && s.endsWith(".py"))
									.map(s -> (s.endsWith("__init__.py") ?
											  s.substring(dir.length(), s.length() - 12) // remove ending "__init__.py"
											: s.substring(dir.length(), s.length() -3))  // remove ending ".py"
											.replace('/', '.'));
						} catch (IOException e) {
							System.out.println("Failed to read jython module file.");
							if (JythonAutocompletionProvider.debug >= 2) e.printStackTrace();
						}
						return null;
					}).flatMap(Function.identity())
					.map(s -> new BasicCompletion(provider, first + " " + s + (first.equals("from") ? " import " : ""), null, "Custom python module"))
					.collect(Collectors.toList()));
			return ac;
		}
		
		final Matcher mm = importMember.matcher(lastLine);
		if (mm.find()) {
			// Complete member name
			final String pkgName = mm.group(1),
					     member = mm.group(2) == null ? "" : mm.group(2);
			// Check that the module exists
			final NModuleType mod = Scope.loadPythonModule(pkgName);
			if (null != mod && !mod.getTable().keySet().isEmpty()) {
				return mod.getTable().keySet().stream()
					.filter(s -> s.startsWith(member))
					.map(s -> new BasicCompletion(provider, "from " + pkgName + " import " + s, null, null)) // todo call "help" on that function
					.collect(Collectors.toList());
			}
			if (null != mod) {
				// Module exists but its __init__.py is empty. Look into its folder
				final ArrayList<Completion> ac = new ArrayList<>();
				for (final String dir : Scope.indexer.getLoadPath()) {
					final File fdir = new File(dir + pkgName.replace('.', '/'));
					if (fdir.exists() && fdir.isDirectory()) {
						for (final String filename: fdir.list()) {
							if (filename.startsWith(member) && (new File(fdir.getAbsolutePath() + "/" + filename).isDirectory() || filename.endsWith(".py"))) {
								ac.add(new BasicCompletion(provider,
										"from " + pkgName + " import " + (filename.endsWith(".py") ?
												filename.substring(0, filename.length() -3)
												: filename), null, null));
							}
						}
					}
				}
				return ac;
				
			}
			return Collections.emptyList();
		}

		final Matcher m1 = nameToken.matcher(lastLine);
		if (m1.find())
			return JythonScriptParser.parseAST(codeWithoutLastLine).getLast().findStartsWith(m1.group(2)).stream()
					.map(s -> new BasicCompletion(provider, (lastLine + s.substring(m1.group(2).length())).substring(crop)))
					.collect(Collectors.toList());
		
		final Matcher m2 = dotNameToken.matcher(lastLine);
		if (m2.find()) {
			final String seed = m2.group(3); // can be empty
			// Expand fields and methods of previous class
			// Assume code is correct up to the dot
			// Python has multiple assignment: find out the class of the last left var
			final String code,
            			 varName;
			final Matcher m3 = assign.matcher(lastLine);
			if (m3.find()) {
				// An assignment, e.g. "ip1, ip2 = imp1.getProcessor(), imp2."
				final String[] assignment = lastLine.split("=");
				final String[] names = assignment[0].split(",");
				varName = names[names.length -1].trim();
				code = codeWithoutLastLine  + lastLine.substring(0, lastLine.length() -1 - seed.length()); // without the ending dot and the seed
			} else {
				// Not an assignment, i.e.  "imp.getImage()." or "imp."
				// Find first non-whitespace char
				int start = 0;
				while (Character.isWhitespace(lastLine.charAt(start++)));
				--start;
				String suffix = "";
				if (add_pass) {
					add_pass = false; // don't
					JythonScriptParser.print("Removed ' pass'");
					suffix = ":\n  ";
				}
				varName = "____GRAB____"; // an injected var to capture the returned class
				code = codeWithoutLastLine + (add_pass ? ": pass" : "") + suffix + lastLine.substring(0, start) + varName + " = " + lastLine.substring(start, lastLine.length() - 1 - seed.length());
				JythonScriptParser.print("codeWithoutLastLine:\n" + codeWithoutLastLine);
			}
			final DotAutocompletions da = JythonScriptParser.parseAST(code).getLast().find(varName, DotAutocompletions.EMPTY);
			final String fullPre = lastLine.substring(crop);
			final String pre = fullPre.substring(0, fullPre.lastIndexOf(seed));
			final String lowerCaseSeed = seed.toLowerCase();
			List<Completion> list = da.get().stream()
					.filter(s -> s.getReplacementText().toLowerCase().contains(lowerCaseSeed))
					.map(s -> s.getCompletion(provider, pre + s.getReplacementText(), s.getReplacementText().startsWith(seed) ? 1 : 0))
					.collect(Collectors.toList());
			sortCompletions(list, seed);
			return list;
		}

		return Collections.emptyList();
	}

	@SuppressWarnings("unused")
	private static String removeLastOptionalDot(final String s) {
		return (s != null && s.endsWith(".")) ? s.substring(0, s.length() - 1) : s;
	}

	/**
	 * @param completions The list of completions
	 * @param pre         the text just before the current caret position that could
	 *                    be the start of something auto-completable.
	 */
	private void sortCompletions(final List<Completion> completions, final String pre) {
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
					return o1.getReplacementText().compareTo(o2.getReplacementText());
				else
					return prefix1Index - prefix2Index;
			}
		});
	}
}

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;

/**
 * Rootbyte (How-to-use)
 * 
 * (Step 1) Select the outer folder(s), e.g. SumFileName or FileYouWant.
 * 
 * (Step 2) Choose where the inner folders of the selected outer folders should go (default: the location of the original selected file) and confirm.
 * 
 * (Step 2.5: the process) For each selected folder:
 *           - copy the inner folder to the destination
 *           - delete the selected (outer) folder and everything in it
 * 
 * (Step 3) Only the inner folder remains in the destination with the original selected folder deleted.
 *
 * To run (Java 11+, no separate compile step needed, I used Windows Powershell for this):
 *   java RootByte.java
 */

public class RootByte {

    // Returns the single item inside `outer`, or null if it isn't exactly one folder.
    static Path getOnlyInnerFolder(Path outer) {
        try (Stream<Path> stream = Files.list(outer)) {
            List<Path> items = stream.collect(Collectors.toList());
            if (items.size() == 1 && Files.isDirectory(items.get(0))) {
                return items.get(0);
            }
        } catch (IOException e) {
            // no read in the outer folder, treat as invalid.
        }
        return null;
    }

    // Returns an error message if `outer` can't be processed, or null if it's fine.
    static String findProblem(Path outer) {
        if (getOnlyInnerFolder(outer) == null) {
            return "must contain exactly one item, and it must be a folder";
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Copy / delete helpers
    // ---------------------------------------------------------------

    // Copies the folder `source` (and everything in it) to `target`.
    static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()),
                        StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // Deletes the folder `root` and everything in it.
    static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }
                Files.delete(dir); // folder is empty by now, so this works
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ---------------------------------------------------------------
    // The "process" function.
    // ---------------------------------------------------------------

    
    // Copies the inner folder of `outer` into `destinationDir`, then deletes `outer`.

    static String process(Path outer, Path destinationDir) throws IOException {
        Path inner = getOnlyInnerFolder(outer);
        Path finalPath = destinationDir.resolve(inner.getFileName());

        // The destination can't be inside the folder we're about to delete.
        if (destinationDir.toAbsolutePath().normalize().startsWith(outer.toAbsolutePath().normalize())) {
            throw new IOException("destination is inside the folder being deleted");
        }

        // Don't merge into or overwrite a different folder that already has this name.
        // (isSameFile covers the TestFile / testfile case on Windows, where the
        // inner folder's final spot is the outer folder's own spot.)
        if (Files.exists(finalPath) && !Files.isSameFile(finalPath, outer)) {
            throw new IOException("'" + inner.getFileName() + "' already exists in the destination");
        }

        // Copy to a temporary name first. This avoids a name clash when the inner
        // folder has the same name as the outer one, and means a failed copy
        // never touches the original.
        Path temp = destinationDir.resolve("__flatten_tmp_" + UUID.randomUUID().toString().substring(0, 8));
        try {
            copyTree(inner, temp);
        } catch (IOException e) {
            try {
                deleteTree(temp); // clean up the partial copy
            } catch (IOException ignored) {
            }
            throw e;
        }

        deleteTree(outer);                 // delete the selected folder and its content
        Files.move(temp, finalPath);       // give the copy its real name

        return "OK: " + outer.getFileName() + File.separator + inner.getFileName()
                + "  ->  " + finalPath;
    }

    // ---------------------------------------------------------------
    // Dialogs
    // ---------------------------------------------------------------

    static File[] pickFolders(String title, boolean multiple) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(multiple);
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
            return new File[0];
        }
        return multiple ? chooser.getSelectedFiles() : new File[] { chooser.getSelectedFile() };
    }

    public static void main(String[] args) {
        // Step 1: Selecting the "outer" folders
        File[] picked = pickFolders("Select the folder(s) to \"Bite\" (each must contain only one folder)", true);
        if (picked.length == 0) {
            return;
        }

        List<Path> valid = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (File f : picked) {
            Path p = f.toPath();
            String problem = findProblem(p);
            if (problem == null) {
                valid.add(p);
            } else {
                problems.add(p.getFileName() + ": " + problem);
            }
        }

        if (valid.isEmpty()) {
            JOptionPane.showMessageDialog(null,
                    "Error. Nothing can be processed:\n\n" + String.join("\n", problems),
                    "Flatten Folders", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Step 2a: choosing the destination of the "inner" folder(s)
        String[] options = { "Default (original location)", "Choose another folder...", "Cancel" };

        int choice = JOptionPane.showOptionDialog(null, "Where should the \"inner\" folder(s) go?\n\n" + "Original location = right next to each selected folder.",
        "Flatten Folders", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        
        Path chosenDestination = null; // null represents "original location"

        if (choice == 1) {
            File[] dest = pickFolders("Choose where the inner folder(s) should go", false);
            if (dest.length == 0) {
                return;
            }
            chosenDestination = dest[0].toPath();
        } else if (choice != 0) {
            return; // cancelled
        }

        // Step 2b: confirmation before processing
        StringBuilder summary = new StringBuilder("These folders will be DELETED, keeping only the folder inside:\n\n");
        for (Path p : valid) {
            summary.append("  ").append(p.getFileName()).append("  ->  keeps ").append(getOnlyInnerFolder(p).getFileName()).append("\n");
        }
        summary.append("\nDestination: ").append(chosenDestination == null ? "next to each original folder" : chosenDestination).append("\n");
        if (!problems.isEmpty()) {
            summary.append("\nSkipped (errors):\n  ").append(String.join("\n  ", problems)).append("\n");
        }
        summary.append("\nContinue?");

        int confirm = JOptionPane.showConfirmDialog(null, summary.toString(), "Confirm", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        // Step 3: processing the folders
        StringBuilder results = new StringBuilder();
        for (Path outer : valid) {
            Path destinationDir = (chosenDestination != null) ? chosenDestination : outer.getParent();
            try {
                results.append(process(outer, destinationDir)).append("\n");
            } catch (IOException e) {
                results.append("ERROR: ").append(outer.getFileName()).append(": ").append(e.getMessage()).append("\n");
            }
        }
        for (String p : problems) {
            results.append("SKIPPED: ").append(p).append("\n");
        }

        System.out.println(results);
        JOptionPane.showMessageDialog(null, results.toString(), "Done", JOptionPane.INFORMATION_MESSAGE);
    }
}

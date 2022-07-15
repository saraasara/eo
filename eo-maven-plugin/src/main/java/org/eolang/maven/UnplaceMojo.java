/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016-2022 Yegor Bugayenko
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.eolang.maven;

import com.jcabi.log.Logger;
import com.yegor256.tojos.Tojo;
import com.yegor256.tojos.Tojos;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Set;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.cactoos.set.SetOf;

/**
 * It deletes binary files, which were previously copied by "place" mojo.
 *
 * @since 0.11
 * @checkstyle ClassDataAbstractionCouplingCheck (500 lines)
 * @checkstyle ExecutableStatementCountCheck (500 lines)
 */
@Mojo(
    name = "unplace",
    defaultPhase = LifecyclePhase.PREPARE_PACKAGE,
    threadSafe = true
)
@SuppressWarnings("PMD.ImmutableField")
public final class UnplaceMojo extends SafeMojo {

    /**
     * The path to a text file where paths of all added
     * .class (and maybe others) files are placed.
     * @checkstyle MemberNameCheck (7 lines)
     * @since 0.11.0
     */
    @Parameter(
        property = "eo.placed",
        required = true,
        defaultValue = "${project.build.directory}/eo/placed.csv"
    )
    private File placed;

    /**
     * Format of "placed" file ("json" or "csv").
     * @checkstyle MemberNameCheck (7 lines)
     * @checkstyle VisibilityModifierCheck (5 lines)
     */
    @SuppressWarnings("PMD.AvoidDuplicateLiterals")
    @Parameter(property = "eo.placedFormat", required = true, defaultValue = "csv")
    private String placedFormat = "csv";

    /**
     * List of inclusion GLOB filters for unplacing (these files will be removed for sure).
     * @since 0.24
     * @checkstyle MemberNameCheck (7 lines)
     */
    @Parameter
    private Set<String> mandatoryUnplace = new SetOf<>();

    /**
     * List of inclusion GLOB filters for placing (ONLY these files will stay).
     * @since 0.24
     * @checkstyle MemberNameCheck (7 lines)
     */
    @Parameter
    private Set<String> selectivelyPlace = new SetOf<>();

    @Override
    public void exec() throws IOException {
        if (this.placed.exists()) {
            this.placeThem();
        } else {
            Logger.info(
                this, "The list of placed binaries is absent: %s",
                Save.rel(this.placed.toPath())
            );
        }
    }

    /**
     * Place what's necessary.
     * @throws IOException If fails
     */
    @SuppressWarnings("PMD.CyclomaticComplexity")
    public void placeThem() throws IOException {
        final Collection<Tojo> tojos = new Catalog(
            this.placed.toPath(), this.placedFormat
        ).make().select(t -> "class".equals(t.get("kind")));
        int unplaced = 0;
        for (final Tojo tojo : tojos) {
            final String related = tojo.get("related");
            if (!this.selectivelyPlace.isEmpty()
                && UnplaceMojo.inside(related, this.selectivelyPlace)) {
                Logger.warn(
                    this,
                    // @checkstyle LineLength (1 line)
                    "The binary %s is removed since it doesn't match 'selectivelyPlace' list of globs",
                    related
                );
                continue;
            }
            final Path path = Paths.get(tojo.get(Tojos.KEY));
            if (!tojo.get("hash").equals(new FileHash(path).toString())) {
                if (!UnplaceMojo.inside(related, this.mandatoryUnplace)) {
                    Logger.warn(this, "The binary %s looks different, won't unplace", related);
                    continue;
                }
                Logger.info(
                    this,
                    // @checkstyle LineLength (1 line)
                    "The binary %s looks different, but its unplacing is mandatory as 'mandatoryUnplace' option specifies",
                    related
                );
            }
            Files.delete(path);
            Logger.debug(this, "Binary %s deleted", Save.rel(path));
            unplaced += 1;
            final Path dir = path.getParent();
            if (dir.toFile().list().length == 0) {
                Files.delete(dir);
                Logger.debug(this, "Directory %s deleted", Save.rel(dir));
            }
        }
        if (tojos.isEmpty()) {
            Logger.info(
                this, "No binaries were placed into %s, nothing to uplace",
                Save.rel(this.placed.toPath())
            );
        } else if (unplaced == 0) {
            Logger.info(
                this, "No binaries out of %d deleted in %s",
                tojos.size(), Save.rel(this.placed.toPath())
            );
        } else if (unplaced == tojos.size()) {
            Logger.info(
                this, "All %d binari(es) deleted, which were found in %s",
                tojos.size(), Save.rel(this.placed.toPath())
            );
        } else {
            Logger.info(
                this, "Just %d binari(es) out of %d deleted in %s",
                tojos.size(), tojos.size(), Save.rel(this.placed.toPath())
            );
        }
    }

    /**
     * This file is matched by one of the globs?
     * @param related The related name of the file
     * @param globs The globs
     * @return TRUE if inside this list of globx
     */
    private static boolean inside(final String related, final Iterable<String> globs) {
        boolean found = false;
        for (final String glob : globs) {
            found = UnplaceMojo.matches(related, glob);
            if (found) {
                break;
            }
        }
        return found;
    }

    /**
     * The file matches the glob?
     * @param related The related name of the file
     * @param glob The glob
     * @return TRUE if matches
     */
    private static boolean matches(final String related, final String glob) {
        return FileSystems.getDefault().getPathMatcher(
            String.format("glob:%s", glob)
        ).matches(Paths.get(related));
    }

}

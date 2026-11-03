package com.newtube.mobile.ui.playback;

import static com.newtube.mobile.ui.playback.RatingUndo.DISLIKE;
import static com.newtube.mobile.ui.playback.RatingUndo.LIKE;
import static com.newtube.mobile.ui.playback.RatingUndo.NONE;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** NEWTUBE(snackbar): Undo puts back the rating from before the tap, not just the tapped thumb. */
public class RatingUndoTest {
    private static final int UP = 11;
    private static final int DOWN = 22;

    @Test
    public void likedThenDislikedUndoesBackToLiked() {
        // before LIKE, the tap made it DISLIKE: Undo taps Like (which also clears the dislike)
        assertEquals(UP, RatingUndo.tapFor(DISLIKE, LIKE, UP, DOWN));
    }

    @Test
    public void dislikedThenLikedUndoesBackToDisliked() {
        assertEquals(DOWN, RatingUndo.tapFor(LIKE, DISLIKE, UP, DOWN));
    }

    @Test
    public void aNewRatingUndoesBackToUnrated() {
        assertEquals(UP, RatingUndo.tapFor(LIKE, NONE, UP, DOWN));
        assertEquals(DOWN, RatingUndo.tapFor(DISLIKE, NONE, UP, DOWN));
    }

    @Test
    public void aRemovedRatingComesBack() {
        assertEquals(UP, RatingUndo.tapFor(NONE, LIKE, UP, DOWN));
        assertEquals(DOWN, RatingUndo.tapFor(NONE, DISLIKE, UP, DOWN));
    }

    @Test
    public void nothingToDoWhenAlreadyThere() {
        assertEquals(0, RatingUndo.tapFor(LIKE, LIKE, UP, DOWN));
    }

    @Test
    public void ratingReadsTheThumbs() {
        assertEquals(LIKE, RatingUndo.rating(true, false));
        assertEquals(DISLIKE, RatingUndo.rating(false, true));
        assertEquals(NONE, RatingUndo.rating(false, false));
    }

    @Test
    public void aLikeMovesTheCountByOne() {
        assertEquals("134", RatingUndo.likeCount("133", false, true));
        assertEquals("132", RatingUndo.likeCount("133", true, false));
        assertEquals("133", RatingUndo.likeCount("133", true, true)); // the count already holds it
        assertEquals("1000", RatingUndo.likeCount("999", false, true));
        assertEquals("1,235", RatingUndo.likeCount("1,234", false, true));
        assertEquals("12 344", RatingUndo.likeCount("12 345", true, false));
        assertEquals("1.2K", RatingUndo.likeCount("1.2K", false, true));
        assertEquals("Like", RatingUndo.likeCount("Like", false, true));
    }
}

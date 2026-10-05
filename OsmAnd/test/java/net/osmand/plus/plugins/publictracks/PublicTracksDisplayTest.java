package net.osmand.plus.plugins.publictracks;
import org.junit.Test;
import static org.junit.Assert.*;
public class PublicTracksDisplayTest {
 private PublicTrackSegment segment(long id,double... points){return new PublicTrackSegment(id,"track-"+id,points,100,"Trail "+id);}
 private PublicTracksDisplay display(){return new PublicTracksDisplay(new double[]{0,0,1,1},800,800);}
 @Test public void denseOverlapKeepsTwoButLateBranchSurvives(){
  PublicTracksDisplay d=display();
  for(int i=1;i<=2105;i++)d.add(segment(i,0,.5,1,.5));
  assertEquals(2,d.getSegments().size());assertTrue(d.getThinned());
  d.add(segment(3000,0,.5,.5,.5,.5,.9));
  assertTrue(d.getSegments().stream().anyMatch(s->s.getTrackId().equals("track-3000")));
  assertTrue(d.getSegments().stream().filter(s->s.getTrackId().equals("track-3000")).flatMapToDouble(s->java.util.Arrays.stream(s.getCoordinates())).anyMatch(x->x==.9));
 }
 @Test public void nearbyGpsDriftDoesNotPaintAThickBundle(){
  PublicTracksDisplay d=display();
  for(int i=0;i<100;i++)d.add(segment(i,0,.501+i*.00015,1,.501+i*.00015));
  assertEquals(2,d.getSegments().size());assertTrue(d.getThinned());
  d.add(segment(200,0,.55,1,.55));
  assertTrue(d.getSegments().stream().anyMatch(s->s.getTrackId().equals("track-200")));
 }
 @Test public void distinctBranchesAreNotGloballyCapped(){
  PublicTracksDisplay d=display();for(int i=0;i<40;i++)d.add(segment(i,0,.02+i*.024,1,.02+i*.024));
  assertEquals(40,d.getSegments().size());assertFalse(d.getThinned());
 }
 @Test public void clippingDoesNotConnectAcrossOffscreenExcursions(){
  PublicTracksDisplay d=display();d.add(segment(1,.2,.2,-1,.2,-1,.8,.2,.8));
  assertEquals(2,d.getSegments().size());
  for(PublicTrackSegment s:d.getSegments())for(double p:s.getCoordinates())assertTrue(p>=0&&p<=1);
 }
 @Test public void displayDoesNotMutateOriginalGeometry(){
  double[] points={-.5,.2,.5,.2,1.5,.2};double[] copy=points.clone();
  PublicTrackSegment s=segment(1,points);PublicTracksDisplay d=display();d.add(s);
  assertArrayEquals(copy,s.getCoordinates(),0);assertEquals("Trail 1",d.getSegments().get(0).getName());
 }
}

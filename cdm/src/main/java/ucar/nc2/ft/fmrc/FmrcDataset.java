/*
 * Copyright (c) 1998 - 2010. University Corporation for Atmospheric Research/Unidata
 * Portions of this software were developed by the Unidata Program at the
 * University Corporation for Atmospheric Research.
 *
 * Access and use of this software shall impose the following obligations
 * and understandings on the user. The user is granted the right, without
 * any fee or cost, to use, copy, modify, alter, enhance and distribute
 * this software, and any derivative works thereof, and its supporting
 * documentation for any purpose whatsoever, provided that this entire
 * notice appears in all copies of the software, derivative works and
 * supporting documentation.  Further, UCAR requests that the user credit
 * UCAR/Unidata in any publications that result from the use of this
 * software or in any product that includes this software. The names UCAR
 * and/or Unidata, however, may not be used in any advertising or publicity
 * to endorse or promote any products or commercial entity unless specific
 * written permission is obtained from UCAR/Unidata. The user also
 * understands that UCAR/Unidata is not obligated to provide the user with
 * any support, consulting, training or assistance of any kind with regard
 * to the use, operation and performance of this software nor to provide
 * the user with any updates, revisions, new versions or "bug fixes."
 *
 * THIS SOFTWARE IS PROVIDED BY UCAR/UNIDATA "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL UCAR/UNIDATA BE LIABLE FOR ANY SPECIAL,
 * INDIRECT OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING
 * FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT,
 * NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION
 * WITH THE ACCESS, USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package ucar.nc2.ft.fmrc;

import net.jcip.annotations.GuardedBy;
import net.jcip.annotations.ThreadSafe;
import thredds.inventory.FeatureCollection;
import ucar.nc2.*;
import ucar.nc2.constants.CF;
import ucar.nc2.dt.GridDataset;
import ucar.nc2.util.CancelTask;
import ucar.nc2.util.Misc;
import ucar.nc2.units.DateFormatter;
import ucar.nc2.constants._Coordinate;
import ucar.nc2.constants.FeatureType;
import ucar.nc2.constants.AxisType;
import ucar.nc2.dataset.*;
import ucar.ma2.*;

import java.io.IOException;
import java.util.*;

/**
 * Turn an FmrcInv into a GridDataset. Helper class for Fmrc
 * Currently only the 2D time dataset is supported.
 * Time coordinate values come from the FmrcInv, so there is little I/O here.
 * Non-aggregation variables are either cached or have DatasetProxyReaders ser so the file is opened when the variable needs to be read.
 * <p> The prototype dataset is kept seperate, since the common case is that just the time coordinates have changed.
 * <p/>
 * This replaces ucar.nc2.dt.fmrc.FmrcImpl
 *
 * @author caron
 * @since Jan 19, 2010
 */
@ThreadSafe
public class FmrcDataset {
  private final FeatureCollection.Config config;

  private Object lock = new Object();

  @GuardedBy("lock")
  private NetcdfDataset proto; // once built, the proto doesnt change

  @GuardedBy("lock")
  private GridDataset gds2D; // result dataset. must be threadsafe (immutable?)

  @GuardedBy("lock")
  private GridDataset best; // must be threadsafe (immutable?)

  @GuardedBy("lock")
  private DateFormatter dateFormatter = new DateFormatter();

  private boolean debug = false, debugEnhance = false, debugRead = false;

  public FmrcDataset(FeatureCollection.Config config) {
    this.config = config;
  }

  /**
   * Get the 2D GridDataset
   *
   * @param fmrcInv    based on this inventory
   * @param forceProto create a new proto, else use existing one if there is one
   * @param force      create a new 2D GridDataset, , else use existing one if there is one
   * @param result     use this empty NetcdfDataset, may be null (used by NcML)
   * @return 2D GridDataset
   * @throws IOException on read error
   */
  public GridDataset getNetcdfDataset2D(FmrcInv fmrcInv, boolean forceProto, boolean force, NetcdfDataset result) throws IOException {

    if (proto == null || forceProto || gds2D == null || force) {

      synchronized (lock) {
        GridDataset gds = null;
        NetcdfDataset protoDataset = null;

        if (proto == null || forceProto) {
          HashMap<String, NetcdfDataset> openFilesProto = new HashMap<String, NetcdfDataset>();
          protoDataset = makeProto(fmrcInv, config.protoConfig, openFilesProto);
          gds = buildDataset2D(fmrcInv, protoDataset, true, result);
          closeAll(openFilesProto); // buildDataset2D needs these files open to read cache vars

        } else {

          if (gds2D == null || force)
            gds = buildDataset2D(fmrcInv, proto, false, result);
        }

        // change all at once
        if (protoDataset != null)
          this.proto = protoDataset;
        if (gds != null)
          this.gds2D = gds;
      }

    }

    return gds2D;
  }

    public GridDataset getBest(FmrcInv fmrcInv, boolean forceProto, boolean force, NetcdfDataset result) throws IOException {

    if (proto == null || forceProto || best == null || force) {

      synchronized (lock) {
        GridDataset gds = null;
        NetcdfDataset protoDataset = null;

        if (proto == null || forceProto) {
          HashMap<String, NetcdfDataset> openFilesProto = new HashMap<String, NetcdfDataset>();
          protoDataset = makeProto(fmrcInv, config.protoConfig, openFilesProto);
          gds = buildDatasetBest(fmrcInv, protoDataset, true, result);
          closeAll(openFilesProto);

        } else {

          if (best == null || force)
            gds = buildDatasetBest(fmrcInv, proto, false, result);
        }

        // change all at once
        if (protoDataset != null)
          this.proto = protoDataset;
        if (gds != null)
          this.best = gds;
      }

    }

    return best;
  }

  /////////////////////////////////////////////////////////////////////////////
  // the prototypical dataset

  private NetcdfDataset makeProto(FmrcInv fmrcInv, FeatureCollection.ProtoConfig protoConfig, HashMap<String, NetcdfDataset> openFilesProto) throws IOException {
    System.out.printf("makeProto %n");
    NetcdfDataset result = new NetcdfDataset(); // empty

    // choose some run in in the list
    List<FmrInv> list = fmrcInv.getFmrInv();
    int protoIdx = 0;
    switch (protoConfig.choice) {
      case First:
        protoIdx = 0;
        break;
      case Random:
        Random r = new Random(System.currentTimeMillis());
        protoIdx = r.nextInt(list.size() - 1);
        break;
      case Penultimate:
        protoIdx = Math.max(list.size() - 2, 0);
        break;
      case Latest:
        protoIdx = Math.max(list.size() - 1, 0);
        break;
    }
    FmrInv proto = list.get(protoIdx);

    // create the union of all objects in that run
    // this covers the case where the variables are split across files
    Set<GridDatasetInv> files = proto.getFiles();
    for (GridDatasetInv file : files) {
      NetcdfDataset ncfile = open(file.getLocation(), openFilesProto);  // not acquiring, default enhance
      transferGroup(ncfile.getRootGroup(), result.getRootGroup(), result);
    }

    // some additional global attributes
    Group root = result.getRootGroup();
    root.addAttribute(new Attribute("Conventions", "CF-1.0, " + _Coordinate.Convention));
    root.addAttribute(new Attribute("cdm_data_type", FeatureType.GRID.toString()));

    // remove some attributes that can cause trouble
    root.remove(root.findAttribute(_Coordinate.ModelRunDate));
    root.remove(root.findAttribute("location"));

    result.finish();

    // remove troublesome attributes
    for (Variable v : result.getVariables()) {
      v.removeAttribute(_Coordinate.Axes);
    }

    return result;
  }

  // transfer the objects in src group to the target group, unless that name already exists
  // Dimensions, Variables, Groups are not transferred, but an equivilent object is created with same metadata
  // Attributes and EnumTypedef are transferred, these are immutable with no references to container

  private void transferGroup(Group srcGroup, Group targetGroup, NetcdfDataset target) {
    // group attributes
    DatasetConstructor.transferGroupAttributes(srcGroup, targetGroup);

    // dimensions
    for (Dimension d : srcGroup.getDimensions()) {
      if (null == targetGroup.findDimensionLocal(d.getName())) {
        Dimension newd = new Dimension(d.getName(), d.getLength(), d.isShared(), d.isUnlimited(), d.isVariableLength());
        targetGroup.addDimension(newd);
      }
    }

    // transfer variables - eliminate any references to component files
    for (Variable v : srcGroup.getVariables()) {
      Variable targetV = targetGroup.findVariable(v.getShortName());

      if (null == targetV) { // add it
        if (v instanceof Structure) {
          targetV = new StructureDS(target, targetGroup, null, v.getShortName(), v.getDimensionsString(), v.getUnitsString(), v.getDescription());
          //LOOK - not adding the members here - what to do ??

        } else {
          targetV = new VariableDS(target, targetGroup, null, v.getShortName(), v.getDataType(), v.getDimensionsString(), v.getUnitsString(), v.getDescription());
        }

        DatasetConstructor.transferVariableAttributes(v, targetV);
        targetV.setSPobject(v); //temporary, for non-agg variables when proto is made
        if (v.hasCachedData())
          targetV.setCachedData(v.getCachedData()); //
        targetGroup.addVariable(targetV);
      }
    }

    // nested groups - check if target already has it
    for (Group srcNested : srcGroup.getGroups()) {
      Group nested = targetGroup.findGroup(srcNested.getShortName());
      if (null == nested) {
        nested = new Group(target, targetGroup, srcNested.getShortName());
        targetGroup.addGroup(nested);
        for (EnumTypedef et : srcNested.getEnumTypedefs()) {
          targetGroup.addEnumeration(et);
        }
      }
      transferGroup(srcNested, nested, target);
    }
  }

  /////////////////////////////////////////////////////////
  // constructing the dataset

  private String getRunDimensionName() {
    return "run";
  }

  private String getCoordinateList2D(VariableDS aggVar, FmrcInv.UberGrid ugrid) {
    String coords = "";
    Attribute att = aggVar.findAttribute(CF.COORDINATES);
    if (att != null)
      coords = att.getStringValue();

    return getRunDimensionName() + " " + "forecast_" + ugrid.getTimeCoordName() + " " + coords;
  }

  private void addAttributeInfo(NetcdfDataset result, String attName, String info) {
    Attribute att = result.findGlobalAttribute(attName);
    if (att == null)
      result.addAttribute(null, new Attribute(attName, info));
    else {
      String oldValue = att.getStringValue();
      result.addAttribute(null, new Attribute(attName, oldValue +" \n"+ info));
    }
  }


  /**
   * Build the 2D time dataset, make it immutable so it can be shared across threads
   *
   * @param proto      prototypical dataset
   * @param buildProto if true, finish building proto by adding data or ProxyReader to non-agg variables
   * @param result     place results in here, if null create a new one. must be threadsafe (immutable)
   * @return resulting GridDataset
   * @throws IOException on read error
   */
  private GridDataset buildDataset2D(FmrcInv fmrcInv, NetcdfDataset proto, boolean buildProto, NetcdfDataset result) throws IOException {
    // make a copy, so that this object can coexist with previous incarnations
    if (result == null) result = new NetcdfDataset();
    result.setLocation(fmrcInv.getName());
    transferGroup(proto.getRootGroup(), result.getRootGroup(), result);
    result.finish();
    addAttributeInfo(result, "history", "FMRC 2D Dataset");

    // create runtime aggregation dimension
    List<Date> runtimes = fmrcInv.getRunTimes();
    String runtimeDimName = getRunDimensionName();
    int nruns = runtimes.size();
    Dimension runDim = new Dimension(runtimeDimName, nruns);
    result.removeDimension(null, runtimeDimName); // remove previous declaration, if any
    result.addDimension(null, runDim);

    // deal with promoteGlobalAttribute
    // promoteGlobalAttributes((AggregationOuterDimension.DatasetOuterDimension) typicalDataset);

    DateFormatter dateFormatter = new DateFormatter();
    ProxyReader2D proxyReader2D = new ProxyReader2D();

    // extract a copy of the runtimes for thread safety
    List<Date> runTimes = new ArrayList<Date>(fmrcInv.getRunTimes());

    // create runtime aggregation coordinate variable
    DataType coordType = DataType.DOUBLE; // LOOK getCoordinateType();
    VariableDS runtimeCoordVar = new VariableDS(result, null, null, runtimeDimName, coordType, runtimeDimName, null, null);
    runtimeCoordVar.addAttribute(new Attribute("long_name", "Run time for ForecastModelRunCollection"));
    runtimeCoordVar.addAttribute(new ucar.nc2.Attribute("standard_name", "forecast_reference_time"));
    runtimeCoordVar.addAttribute(new ucar.nc2.Attribute("units", "hours since " + dateFormatter.toDateTimeStringISO(fmrcInv.getBaseDate())));
    runtimeCoordVar.addAttribute(new ucar.nc2.Attribute(_Coordinate.AxisType, AxisType.RunTime.toString()));
    result.removeVariable(null, runtimeCoordVar.getShortName());
    result.addVariable(null, runtimeCoordVar);
    if (debug) System.out.println("FmrcDataset: added runtimeCoordVar " + runtimeCoordVar.getName());

    // deal with runtime coordinates
    ArrayDouble.D1 runCoordVals = new ArrayDouble.D1(nruns);
    int index = 0;
    for (Date runtime : runtimes) {
      double hourOffset = FmrcInv.getOffsetInHours(fmrcInv.getBaseDate(), runtime);
      runCoordVals.setDouble(index++, hourOffset);
    }
    runtimeCoordVar.setCachedData(runCoordVals);

    // make the time coordinate(s) as 2D
    List<Variable> nonAggVars = result.getVariables();
    for (FmrcInv.RunSeq runSeq : fmrcInv.getRunSeqs()) {
      Group newGroup = result.getRootGroup(); // can it be different ??

      int noffsets = runSeq.getNTimeOffsets();
      Dimension timeDim = new Dimension(runSeq.getName(), noffsets);
      result.removeDimension(null, runSeq.getName()); // remove previous declaration, if any
      result.addDimension(null, timeDim);

      DataType dtype = DataType.DOUBLE;
      String dims = getRunDimensionName() + " " + runSeq.getName();
      VariableDS timeVar = new VariableDS(result, newGroup, null, "forecast_" + runSeq.getName(), dtype, dims, null, null); // LOOK could just make a CoordinateAxis1D
      timeVar.addAttribute(new Attribute("long_name", "Forecast time for ForecastModelRunCollection"));
      timeVar.addAttribute(new ucar.nc2.Attribute("standard_name", "time"));
      timeVar.addAttribute(new ucar.nc2.Attribute("units", "hours since " + dateFormatter.toDateTimeStringISO(fmrcInv.getBaseDate())));
      timeVar.addAttribute(new ucar.nc2.Attribute("missing_value", Double.NaN));
      timeVar.addAttribute(new ucar.nc2.Attribute(_Coordinate.AxisType, AxisType.Time.toString()));

      // the old one
      newGroup.removeVariable(runSeq.getName());
      newGroup.addVariable(timeVar);

      ArrayDouble.D2 timeCoordVals = makeTimeCoordinateData2D(fmrcInv.getBaseDate(), runSeq, timeVar);
      timeVar.setCachedData(timeCoordVals);

      if (debug) System.out.println("FmrcDataset: added timeCoord " + timeVar.getName());

      // promote all grid variables to agg variables
      for (FmrcInv.UberGrid ugrid : runSeq.getUberGrids()) {
        VariableDS aggVar = (VariableDS) result.findVariable(ugrid.getName());
        if (aggVar == null)
          System.out.println("HEY whereis " + ugrid.getName());

        // create dimension list
        List<Dimension> dimList = aggVar.getDimensions();
        dimList = dimList.subList(1, dimList.size());  // LOOK assumes time is outer dimension
        dimList.add(0, timeDim);
        dimList.add(0, runDim);

        aggVar.setDimensions(dimList);
        aggVar.setProxyReader(proxyReader2D);
        aggVar.setSPobject(new Vstate2D(ugrid, timeCoordVals, fmrcInv.getBaseDate(), runTimes));
        nonAggVars.remove(aggVar);

        // we need to explicitly list the coordinate axes, because time coord is now 2D
        String coords = getCoordinateList2D(aggVar, ugrid);
        aggVar.removeAttribute(_Coordinate.Axes);
        aggVar.addAttribute(new Attribute(CF.COORDINATES, coords)); // CF

        if (debug) System.out.println("FmrcDataset: added grid " + aggVar.getName());
      }
    }

    result.finish();  // this puts the new dimensions into the global structures

    if (buildProto) {
      // these are the non-agg variables - store data or ProxyReader in proto
      for (Variable v : nonAggVars) {
        Variable protoV = proto.findVariable(v.getName());
        Variable orgV = (Variable) protoV.getSPobject();
        if (v.isCaching() || v.isCoordinateVariable()) { // want to cache
          protoV.setCachedData(orgV.read()); // read from original - store in proto
        } else {
          String location = orgV.getParentGroup().getNetcdfFile().getLocation(); // hmmmmm
          protoV.setProxyReader(new DatasetProxyReader(location));  // keep track of original file
        }
        protoV.setSPobject(null); // clear the reference to orgV
      }
    }

    // these are the non-agg variables - get data or ProxyReader from proto
    for (Variable v : nonAggVars) {
      Variable protoV = proto.findVariable(v.getName());
      if (protoV.hasCachedData()) {
        v.setCachedData(protoV.getCachedData()); // read from original
      } else {
        v.setProxyReader(protoV.getProxyReader());
      }
    }

    CoordSysBuilderIF builder = result.enhance();
    if (debugEnhance) System.out.printf("parseInfo = %s%n", builder.getParseInfo());

    return new ucar.nc2.dt.grid.GridDataset(result);
  }

  private ArrayDouble.D2 makeTimeCoordinateData2D(Date baseDate, FmrcInv.RunSeq runSeq, VariableDS timeVar) { // throws IOException {
    int[] shape = timeVar.getShape();
    int nruntimes = shape[0];
    int noffsets = shape[1];

    ArrayDouble.D2 timeCoordVals = new ArrayDouble.D2(nruntimes, noffsets);
    Index ima = timeCoordVals.getIndex();
    MAMath.setDouble(timeCoordVals, Double.NaN);  // all default to missing

    int runIdx = 0;
    for (TimeCoord tc : runSeq.getTimes()) {
      double runOffset = FmrcInv.getOffsetInHours(baseDate, tc.getRunDate());
      double[] offset = tc.getOffsetHours();
      for (int j = 0; j < offset.length; j++)
        timeCoordVals.setDouble(ima.set(runIdx, j), offset[j] + runOffset); // offset reletive to base date
      runIdx++;
    }

    return timeCoordVals;
  }

  // keep track of the ugrid and 2D time coords for each variable, put in SPobject

  private class Vstate2D {
    FmrcInv.UberGrid ugrid;
    ArrayDouble.D2 timeCoordVals;
    Date baseDate;
    List<Date> runTimes;

    private Vstate2D(FmrcInv.UberGrid ugrid, ArrayDouble.D2 timeCoordVals, Date baseDate, List<Date> runTimes) {
      this.ugrid = ugrid;
      this.timeCoordVals = timeCoordVals;
      this.baseDate = baseDate;
      this.runTimes = runTimes;
    }
  }

  private class ProxyReader2D implements ProxyReader {

    @Override
    public Array reallyRead(Variable mainv, CancelTask cancelTask) throws IOException {
      try {
        return reallyRead(mainv, mainv.getShapeAsSection(), cancelTask);

      } catch (InvalidRangeException e) {
        throw new IOException(e);
      }
    }

    // here is where agg variables get read

    @Override
    public Array reallyRead(Variable mainv, Section section, CancelTask cancelTask) throws IOException, InvalidRangeException {
      Vstate2D vstate = (Vstate2D) mainv.getSPobject();
      FmrcInv.UberGrid ugrid = vstate.ugrid;
      ArrayDouble.D2 timeCoordVals = vstate.timeCoordVals;

      // read the original type - if its been promoted to a new type, the conversion happens after this read
      DataType dtype = (mainv instanceof VariableDS) ? ((VariableDS) mainv).getOriginalDataType() : mainv.getDataType();

      Array allData = Array.factory(dtype, section.getShape());
      int destPos = 0;

      // assumes the first two dimensions are runtime and time: LOOK: ensemble ??
      List<Range> ranges = section.getRanges();
      Range runRange = ranges.get(0);
      Range timeRange = ranges.get(1);
      List<Range> innerSection = ranges.subList(2, ranges.size());

      // keep track of open file - must be local variable for thread safety
      HashMap<String, NetcdfDataset> openFilesRead = new HashMap<String, NetcdfDataset>();

      // iterate over the desired runs
      Range.Iterator runIter = runRange.getIterator();
      while (runIter.hasNext()) {
        int runIdx = runIter.next();
        Date runDate = vstate.runTimes.get(runIdx);

        // iterate over the desired forecast times
        Range.Iterator timeIter = timeRange.getIterator();
        while (timeIter.hasNext()) {
          int timeIdx = timeIter.next();

          // find the inventory for this grid, runtime, and hour
          double offsetHour = timeCoordVals.get(runIdx, timeIdx);
          Inventory inv = findInventory(ugrid, vstate.baseDate, runDate, offsetHour);
          Array result = null;
          if (inv != null) {
            if (debugRead) System.out.printf("HIT %f ", offsetHour);
            result = read(inv, ugrid, innerSection, openFilesRead); // may return null
            result = MAMath.convert(result, dtype); // just in case it need to be converted
          }

          // missing data
          if (result == null) {
            int[] shape = new Section(innerSection).getShape();
            result = ((VariableDS) mainv).getMissingDataArray(shape); // fill with missing values
            if (debugRead) System.out.printf("MISS %f ", offsetHour);
          }

          if (debugRead)
            System.out.printf("%d %d reallyRead %s %d bytes start at %d total size is %d%n", runIdx, timeIdx, mainv.getName(), result.getSize(), destPos, allData.getSize());
          Array.arraycopy(result, 0, allData, destPos, (int) result.getSize());
          destPos += result.getSize();
        }
      }

      // close any files used during this operation
      closeAll(openFilesRead);
      return allData;
    }

    // LOOK replace with hashed lookup ?
    // find the inventory for this grid, runtime, and hour LOOK: ensemble ??

    private Inventory findInventory(FmrcInv.UberGrid ugrid, Date baseDate, Date runDate, double offsetHour) {
      for (FmrInv.GridVariable grid : ugrid.getRuns()) {
        if (grid.getRunDate().equals(runDate)) { // find the right run
          for (GridDatasetInv.Grid inv : grid.getInventory()) { // find the file that matches the offset hour
            double invOffset = offsetHour - FmrcInv.getOffsetInHours(baseDate, inv.tc.getRunDate()); // offset reletive to inv
            double[] offsets = inv.tc.getOffsetHours();
            for (int i = 0; i < offsets.length; i++) {
              if (Misc.closeEnough(offsets[i], invOffset))
                return new Inventory(inv, i);
            }
          }
          return null; // no offsetHour match
        }
      }
      return null; // no runDate match
    }
  }

 /////////////////////////////////////////////////////////////////////////
  // 1D

  private String getCoordinateList1D(VariableDS aggVar, FmrcInv.UberGrid ugrid) {
    String coords = "";
    Attribute att = aggVar.findAttribute(CF.COORDINATES);
    if (att != null)
      coords = att.getStringValue();

    return "forecast_" + ugrid.getTimeCoordName() + " " + coords;
  }

  /**
   * Build the best dataset, make it immutable so it can be shared across threads
   *
   * @param proto      prototypical dataset
   * @param buildProto if true, finish building proto by adding data or ProxyReader to non-agg variables
   * @param result     place results in here, if null create a new one. must be threadsafe (immutable)
   * @return resulting GridDataset
   * @throws IOException on read error
   */
  private GridDataset buildDatasetBest(FmrcInv fmrcInv, NetcdfDataset proto, boolean buildProto, NetcdfDataset result) throws IOException {
    // make a copy, so that this object can coexist with previous incarnations
    if (result == null) result = new NetcdfDataset();
    result.setLocation(fmrcInv.getName());
    transferGroup(proto.getRootGroup(), result.getRootGroup(), result);
    result.finish();
    addAttributeInfo(result, "history", "FMRC Best Dataset");

    ProxyReader1D proxyReader1D = new ProxyReader1D();

    // make the time coordinate(s) for each runSeq
    List<Variable> nonAggVars = result.getVariables();
    for (FmrcInv.RunSeq runSeq : fmrcInv.getRunSeqs()) {
      Group newGroup = result.getRootGroup(); // can it be different ??

      Dimension timeDim = null;
      VariableDS timeCoord = null;
      ArrayDouble.D1 timeCoordVals = null;

      // promote all grid variables to agg variables
      for (FmrcInv.UberGrid ugrid : runSeq.getUberGrids()) {
        FmrcInv.TimeInventory inv = fmrcInv.makeBestInventory(ugrid);

        if (timeCoord == null) {
          String varname = runSeq.getName();
          timeCoord = makeTimeCoordinate(result, newGroup, varname, fmrcInv.getBaseDate(), inv);
          newGroup.removeVariable(varname);
          newGroup.addVariable(timeCoord);

          timeCoordVals = (ArrayDouble.D1) timeCoord.getCachedData();
          timeDim = timeCoord.getDimension(0);
        }

        VariableDS aggVar = (VariableDS) result.findVariable(ugrid.getName());
        if (aggVar == null)
          System.out.println("HEY whereis " + ugrid.getName());

        // create dimension list
        List<Dimension> dimList = aggVar.getDimensions();
        dimList = dimList.subList(1, dimList.size());  // LOOK assumes time is outer dimension
        dimList.add(0, timeDim);

        aggVar.setDimensions(dimList);
        aggVar.setProxyReader(proxyReader1D);
        aggVar.setSPobject(new Vstate1D(ugrid, timeCoordVals, fmrcInv.getBaseDate(), inv));
        nonAggVars.remove(aggVar);

        // we need to explicitly list the coordinate axes
        String coords = getCoordinateList1D(aggVar, ugrid);
        aggVar.removeAttribute(_Coordinate.Axes);
        aggVar.addAttribute(new Attribute(CF.COORDINATES, coords)); // CF

        if (debug) System.out.println("FmrcDataset: added grid " + aggVar.getName());
      }
    }

    result.finish();  // this puts the new dimensions into the global structures

    if (buildProto) {
      // these are the non-agg variables - store data or ProxyReader in proto
      for (Variable v : nonAggVars) {
        Variable protoV = proto.findVariable(v.getName());
        Variable orgV = (Variable) protoV.getSPobject();
        if (v.isCaching() || v.isCoordinateVariable()) { // want to cache
          protoV.setCachedData(orgV.read()); // read from original - store in proto
        } else {
          String location = orgV.getParentGroup().getNetcdfFile().getLocation(); // hmmmmm
          protoV.setProxyReader(new DatasetProxyReader(location));  // keep track of original file
        }
        protoV.setSPobject(null); // clear the reference to orgV
      }
    }

    // these are the non-agg variables - get data or ProxyReader from proto
    for (Variable v : nonAggVars) {
      Variable protoV = proto.findVariable(v.getName());
      if (protoV.hasCachedData()) {
        v.setCachedData(protoV.getCachedData()); // read from original
      } else {
        v.setProxyReader(protoV.getProxyReader());
      }
    }

    CoordSysBuilderIF builder = result.enhance();
    if (debugEnhance) System.out.printf("parseInfo = %s%n", builder.getParseInfo());

    return new ucar.nc2.dt.grid.GridDataset(result);
  }

  private VariableDS makeTimeCoordinate(NetcdfDataset result, Group group, String varName, Date baseDate, FmrcInv.TimeInventory timeInv) {
    int count = 0;
    double[] offsets = timeInv.tc.getOffsetHours();
    for (int idx = 0; idx < offsets.length; idx++) {
      if (timeInv.useGrid[idx] != null)
        count++;
    }
    int ntimes = count;

    Dimension timeDim = new Dimension(varName, ntimes);
    result.removeDimension(group, varName); // remove previous declaration, if any
    result.addDimension(group, timeDim);

    // construct the values
    ArrayDouble.D1 timeCoordVals = new ArrayDouble.D1(ntimes);
    MAMath.setDouble(timeCoordVals, Double.NaN);  // all default to missing
    Index ima = timeCoordVals.getIndex();
    count = 0;
    for (int idx = 0; idx < offsets.length; idx++) {
      if (timeInv.useGrid[idx] != null)
         timeCoordVals.setDouble(ima.set(count++), offsets[idx]);
    }

    // construct the coord var
    DataType dtype = DataType.DOUBLE;
    VariableDS timeVar = new VariableDS(result, group, null, "forecast_" + varName, dtype, varName, null, null); // LOOK could just make a CoordinateAxis1D
    timeVar.addAttribute(new Attribute("long_name", "Forecast time for ForecastModelRunCollection"));
    timeVar.addAttribute(new ucar.nc2.Attribute("standard_name", "time"));
    timeVar.addAttribute(new ucar.nc2.Attribute("units", "hours since " + dateFormatter.toDateTimeStringISO(baseDate)));
    timeVar.addAttribute(new ucar.nc2.Attribute("missing_value", Double.NaN));
    timeVar.addAttribute(new ucar.nc2.Attribute(_Coordinate.AxisType, AxisType.Time.toString()));
    if (debug) System.out.println("FmrcDataset: added timeCoord " + timeVar.getName());

    timeVar.setCachedData(timeCoordVals);

    return timeVar;
  }

  private class Vstate1D {
    FmrcInv.UberGrid ugrid;
    ArrayDouble.D1 timeCoordVals;
    FmrcInv.TimeInventory inv;
    Date baseDate;

    private Vstate1D(FmrcInv.UberGrid ugrid, ArrayDouble.D1 timeCoordVals, Date baseDate, FmrcInv.TimeInventory inv) {
      this.ugrid = ugrid;
      this.timeCoordVals = timeCoordVals;
      this.baseDate = baseDate;
      this.inv = inv;
    }
  }

  private class ProxyReader1D implements ProxyReader {

    @Override
    public Array reallyRead(Variable mainv, CancelTask cancelTask) throws IOException {
      try {
        return reallyRead(mainv, mainv.getShapeAsSection(), cancelTask);

      } catch (InvalidRangeException e) {
        throw new IOException(e);
      }
    }

    // here is where 1D agg variables get read
    @Override
    public Array reallyRead(Variable mainv, Section section, CancelTask cancelTask) throws IOException, InvalidRangeException {
      Vstate1D vstate = (Vstate1D) mainv.getSPobject();
      ArrayDouble.D1 timeCoordVals = vstate.timeCoordVals;

      // read the original type - if its been promoted to a new type, the conversion happens after this read
      DataType dtype = (mainv instanceof VariableDS) ? ((VariableDS) mainv).getOriginalDataType() : mainv.getDataType();

      Array allData = Array.factory(dtype, section.getShape());
      int destPos = 0;

      // assumes the first dimension is time: LOOK: what about ensemble ??
      List<Range> ranges = section.getRanges();
      Range timeRange = ranges.get(0);
      List<Range> innerSection = ranges.subList(1, ranges.size());

      // keep track of open files - must be local variable for thread safety
      HashMap<String, NetcdfDataset> openFilesRead = new HashMap<String, NetcdfDataset>();

      // iterate over the desired forecast times
      Range.Iterator timeIter = timeRange.getIterator();
      while (timeIter.hasNext()) {
        int timeIdx = timeIter.next();

        // find the inventory for this grid, runtime, and hour
        double offsetHour = timeCoordVals.get(timeIdx);
        Inventory inv = findInventory(vstate.inv, vstate.baseDate, offsetHour);
        Array result = null;
        if (inv != null) {
          if (debugRead) System.out.printf("HIT %f ", offsetHour);
          result = read(inv, vstate.ugrid, innerSection, openFilesRead); // may return null
          result = MAMath.convert(result, dtype); // just in case it need to be converted
        }

        // missing data
        if (result == null) {
          int[] shape = new Section(innerSection).getShape();
          result = ((VariableDS) mainv).getMissingDataArray(shape); // fill with missing values
          if (debugRead) System.out.printf("MISS %f ", offsetHour);
        }

        if (debugRead)
          System.out.printf("%d reallyRead %s %d bytes start at %d total size is %d%n", timeIdx, mainv.getName(), result.getSize(), destPos, allData.getSize());
        Array.arraycopy(result, 0, allData, destPos, (int) result.getSize());
        destPos += result.getSize();
      }

      // close any files used during this operation
      closeAll(openFilesRead);
      return allData;
    }

    private Inventory findInventory(FmrcInv.TimeInventory timeInv, Date baseDate, double offsetHour) {
      // stupid to do a linear search
      FmrInv.GridVariable grid = null;
      double[] offsets = timeInv.tc.getOffsetHours();
      for (int idx = 0; idx < offsets.length; idx++) {
        if (Misc.closeEnough(offsets[idx], offsetHour)) {
          grid = timeInv.useGrid[idx];
          break;
        }
      }
      if (grid == null)
        throw new IllegalStateException("cant find offsetHour in FmrcInv.TimeInventory" + offsetHour);

      // look - could do better and keep the GridDatasetInv.Grid instead of the FmrInv.GridVariabl in the TimeInventory
      // find the file that matches the offset hour
      for (GridDatasetInv.Grid inv : grid.getInventory()) {
        double invOffset = offsetHour - FmrcInv.getOffsetInHours(baseDate, inv.tc.getRunDate()); // offset reletive to inv
        offsets = inv.tc.getOffsetHours();
        for (int i = 0; i < offsets.length; i++) {
          if (Misc.closeEnough(offsets[i], invOffset))
            return new Inventory(inv, i);
        }
      }
      return null; // no match
    }
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////////

  private class Inventory {
    GridDatasetInv.Grid dataset;
    int index; // time index in the file named by inv

    private Inventory(GridDatasetInv.Grid dataset, int index) {
      this.dataset = dataset;
      this.index = index;
    }
  }

  // the general case is to get only one time per read - probably not too inefficient, eg GRIB, except maybe for remote reads

  private Array read(Inventory invHour, FmrcInv.UberGrid ugrid, List<Range> innerSection, HashMap<String, NetcdfDataset> openFilesRead) throws IOException, InvalidRangeException {
    NetcdfFile ncfile = open(invHour.dataset.getLocation(), openFilesRead);
    Variable v = ncfile.findVariable(ugrid.getName());

    // v could be missing, return missing data i think
    if (v == null) return null;

    // assume time is first dimension LOOK: out of-order; ensemble; section different ??
    Range timeRange = new Range(invHour.index, invHour.index);
    Section s = new Section(innerSection);
    s.insertRange(0, timeRange);
    return v.read(s);
  }

  // this is mutable
  // private HashMap<String, NetcdfDataset> openFilesRead = new HashMap<String, NetcdfDataset>();

  private NetcdfDataset open(String location, HashMap<String, NetcdfDataset> openFiles) throws IOException {
    NetcdfDataset ncfile = null;

    if (openFiles != null) {
      ncfile = openFiles.get(location);
      if (ncfile != null) return ncfile;
    }

    ncfile = NetcdfDataset.acquireDataset(location, null);  // not acquiring, default enhance
    if (openFiles != null) {
      openFiles.put(location, ncfile);
    }
    return ncfile;
  }

  private void closeAll(HashMap<String, NetcdfDataset> openFiles) throws IOException {
    for (NetcdfDataset ncfile : openFiles.values()) {
      ncfile.close();
    }
    openFiles.clear();
  }

  ////////////////////////////////////////////////////////////////////////////////////
  // all normal (non agg, non cache) variables must use a proxy to acquire the file
  // the openFiles map is null - should be ok since its a non-agg, so file gets opened (and closed) for each read.

  protected class DatasetProxyReader implements ProxyReader {
    String location;

    DatasetProxyReader(String location) {
      this.location = location;
    }

    public Array reallyRead(Variable client, CancelTask cancelTask) throws IOException {
      NetcdfFile ncfile = null;
      try {
        ncfile = open(location, null);
        if ((cancelTask != null) && cancelTask.isCancel()) return null;
        Variable proxyV = findVariable(ncfile, client);
        return proxyV.read();
      } finally {
        ncfile.close();
      }
    }

    public Array reallyRead(Variable client, Section section, CancelTask cancelTask) throws IOException, InvalidRangeException {
      NetcdfFile ncfile = null;
      try {
        ncfile = open(location, null);
        Variable proxyV = findVariable(ncfile, client);
        if ((cancelTask != null) && cancelTask.isCancel()) return null;
        return proxyV.read(section);
      } finally {
        ncfile.close();
      }
    }
  }

  protected Variable findVariable(NetcdfFile ncfile, Variable client) {
    Variable v = ncfile.findVariable(client.getName());
    if (v == null) {  // might be renamed
      VariableEnhanced ve = (VariableEnhanced) client;
      v = ncfile.findVariable(ve.getOriginalName());
    }
    return v;
  }

}
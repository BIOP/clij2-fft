
package net.haesleinhuepf.clijx.plugins;

import static net.haesleinhuepf.clijx.plugins.OpenCLFFTUtility.cropExtended;
import static net.haesleinhuepf.clijx.plugins.OpenCLFFTUtility.padFFTInputMirror;
import static net.haesleinhuepf.clijx.plugins.OpenCLFFTUtility.padShiftFFTKernel;

import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
import net.haesleinhuepf.clij.coremem.enums.NativeTypeEnum;
import net.haesleinhuepf.clij.macro.CLIJMacroPlugin;
import net.haesleinhuepf.clij.macro.CLIJOpenCLProcessor;
import net.haesleinhuepf.clij.macro.documentation.OffersDocumentation;
import net.haesleinhuepf.clij2.AbstractCLIJ2Plugin;
import net.haesleinhuepf.clij2.CLIJ2;
import net.haesleinhuepf.clij2.utilities.HasAuthor;
import net.haesleinhuepf.clij2.utilities.HasClassifiedInputOutput;
import net.haesleinhuepf.clij2.utilities.IsCategorized;
import net.imagej.ops.OpService;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.loops.LoopBuilder;

import org.jocl.NativePointerObject;
import org.scijava.Context;
import org.scijava.command.CommandService;
import org.scijava.plugin.Plugin;

import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

import ij.IJ;

@Plugin(type = CLIJMacroPlugin.class, name = "CLIJx_deconvolveRichardsonLucyFFT")
public class DeconvolveRichardsonLucyFFT extends AbstractCLIJ2Plugin implements
	CLIJMacroPlugin, CLIJOpenCLProcessor, OffersDocumentation, HasAuthor, HasClassifiedInputOutput, IsCategorized
{

	private static OpService ops;
	private static Context ctx;
	static {
		// this initializes the SciJava platform.
		// See https://forum.image.sc/t/compatibility-of-imagej-tensorflow-with-imagej1/41295/2
		ctx = (Context) IJ.runPlugIn("org.scijava.Context", "");
		if (ctx == null) ctx = new Context(CommandService.class, OpService.class);
		ops = ctx.getService(OpService.class);
	}

	@Override
	public boolean executeCL() {
		
		float regularizationFactor = 0.0f;
		
		if (args.length==5) {
			regularizationFactor = ((Double)(args[4])).floatValue();
		}
		
		boolean nonCirculant = false;
		
		if (args.length==6) {
			nonCirculant = (boolean)((args[5]));
		}
		
		boolean result = deconvolveRichardsonLucyFFT(getCLIJ2(), (ClearCLBuffer) (args[0]),
			(ClearCLBuffer) (args[1]), (ClearCLBuffer) (args[2]), asInteger(args[3]), regularizationFactor, nonCirculant);
		return result;
	}
 	
	public static boolean deconvolveRichardsonLucyFFT(CLIJ2 clij2, ClearCLBuffer input,
							ClearCLBuffer psf, ClearCLBuffer deconvolved, int num_iterations) 
	{
		return deconvolveRichardsonLucyFFT(clij2, input, psf, deconvolved, num_iterations, 0.0f, false); 
	}
	
	/**
	 * Convert images to float (if not already float), normalize PSF and call Richardson Lucy 
	 * 
	 * @param clij2
	 * @param input
	 * @param psf
	 * @param deconvolved
	 * @param num_iterations
	 * 
	 * @return true if successful
	 */
	public static boolean deconvolveRichardsonLucyFFT(CLIJ2 clij2, ClearCLBuffer input,
													  ClearCLBuffer psf, ClearCLBuffer deconvolved, int num_iterations, 
													  float regularizationFactor, boolean nonCirculant)
	{
		ClearCLBuffer input_float = input;
		
		boolean input_converted=false;
		
		if (input_float.getNativeType() != NativeTypeEnum.Float) {
			input_float = clij2.create(input.getDimensions(), NativeTypeEnum.Float);
			clij2.copy(input, input_float);
			input_converted=true;
		}

		boolean psf_converted=false;
		ClearCLBuffer psf_float = psf;
		if (psf.getNativeType() != NativeTypeEnum.Float) {
			psf_float = clij2.create(psf
					.getDimensions(), NativeTypeEnum.Float);
			clij2.copy(psf, psf_float);
			psf_converted=true;
		}

		// normalize PSF so that it's sum is one 
		ClearCLBuffer psf_normalized = clij2.create(psf_float);
		
		OpenCLFFTUtility.normalize(clij2, psf_float, psf_normalized);
		
		long start = System.currentTimeMillis();
		
		// deconvolve
		extendAndDeconvolveRichardsonLucyFFT(clij2, input_float, 
			psf_normalized, deconvolved, num_iterations, regularizationFactor, nonCirculant);

		long end = System.currentTimeMillis();
		
		System.out.println("Deconvolve time "+(end-start));

		if (input_converted) {
			input_float.close();
		}
		
		if (psf_converted) {
			psf_float.close();
		}
		
		psf_normalized.close();

		return true;
	}

  /**
   * Extend image and PSF to next supported FFT size and call Richardson Lucy
   * 
   * @param clij2
   * @param input
   * @param psf
   * @param output
   * @param num_iterations
   * 
   * @return true if successful
   * 
   * TODO error handling
   */
	private static boolean extendAndDeconvolveRichardsonLucyFFT(CLIJ2 clij2, ClearCLBuffer input,
										ClearCLBuffer psf, ClearCLBuffer output, int num_iterations, float regularizationFactor, boolean nonCirculant)
	{

		//ClearCLBuffer input_extended = padFFT(clij2, input, psf);
		ClearCLBuffer input_extended = padFFTInputMirror(clij2, input, psf, ops);
		ClearCLBuffer deconvolved_extended = clij2.create(input_extended);
		ClearCLBuffer psf_extended = clij2.create(input_extended);
		
		clij2.copy(input_extended, deconvolved_extended);
		
		padShiftFFTKernel(clij2, psf, psf_extended);
		
		long[] extendedDims = input_extended.getDimensions();
		long[] originalDims = input.getDimensions();
	
		ClearCLBuffer normalization_factor=null;
		
		if (nonCirculant) {
			normalization_factor = createNormalizationFactor(clij2, new FinalDimensions(extendedDims[0],extendedDims[1],extendedDims[2]),  
				new FinalDimensions(originalDims[0],originalDims[1],originalDims[2]), psf_extended);
		}
		runRichardsonLucyGPU(clij2, input_extended, psf_extended, deconvolved_extended, normalization_factor, num_iterations, regularizationFactor);

		cropExtended(clij2, deconvolved_extended, output);
		
		clij2.release(psf_extended);
		clij2.release(input_extended);
		clij2.release(deconvolved_extended);

		return true;
	}


	/**
	 * run Richardson Lucy deconvolution
	 * 
	 * @param clij2
	 * @param gpuImg
	 * @param gpuPSF
	 * @param output
	 * @param num_iterations
	 * 
	 * @return Deconvolved CLBuffer
	 * TODO proper error handling
	 */
	public static boolean runRichardsonLucyGPU(CLIJ2 clij2, ClearCLBuffer gpuImg,
										 ClearCLBuffer gpuPSF, ClearCLBuffer output, ClearCLBuffer gpuNormal, 
										 int num_iterations, float regularizationFactor)
	{

		// copy the image to use as the initial value
		ClearCLBuffer gpuEstimate = output;

		// Get the CL Buffers, context, queue and device as long native pointers
		long longPointerImg = ((NativePointerObject) (gpuImg.getPeerPointer()
				.getPointer())).getNativePointer();
		long longPointerPSF = ((NativePointerObject) (gpuPSF.getPeerPointer()
				.getPointer())).getNativePointer();
		long longPointerEstimate = ((NativePointerObject) (gpuEstimate
				.getPeerPointer().getPointer())).getNativePointer();
		long longPointerNormal=0;
		
		if (gpuNormal!=null) {
		 longPointerNormal = ((NativePointerObject) (gpuNormal
				.getPeerPointer().getPointer())).getNativePointer();
		}
		
		long l_context = ((NativePointerObject) (clij2.getCLIJ().getClearCLContext()
				.getPeerPointer().getPointer())).getNativePointer();
		long l_queue = ((NativePointerObject) (clij2.getCLIJ().getClearCLContext()
				.getDefaultQueue().getPeerPointer().getPointer())).getNativePointer();
		long l_device = ((NativePointerObject) clij2.getCLIJ().getClearCLContext()
				.getDevice().getPeerPointer().getPointer()).getNativePointer();

		// call the decon wrapper (n iterations of RL)
		clij2fftWrapper.deconv3d_32f_lp_tv(num_iterations, regularizationFactor, gpuImg.getDimensions()[0], gpuImg
						.getDimensions()[1], gpuImg.getDimensions()[2], longPointerImg,
				longPointerPSF, longPointerEstimate, longPointerNormal, l_context, l_queue,
				l_device);

		return true;
	}
	
	private static ClearCLBuffer createNormalizationFactor(CLIJ2 clij2, final Dimensions paddedDimensions,
		final Dimensions originalDimensions, ClearCLBuffer psf) {
		
		// diagnostic
		System.out.println("CreateNormalizationFactor for dimensions " +
			paddedDimensions.dimension(0) + " " + paddedDimensions.dimension(1) +
			" " + paddedDimensions.dimension(2));

		// compute convolution interval
		final long[] start = new long[paddedDimensions.numDimensions()];
		final long[] end = new long[paddedDimensions.numDimensions()];

		for (int d = 0; d < originalDimensions.numDimensions(); d++) {
			final long offset = (paddedDimensions.dimension(d) - originalDimensions
				.dimension(d)) / 2;
			start[d] = offset;
			end[d] = start[d] + originalDimensions.dimension(d) - 1;
			
		}
		
		final Interval convolutionInterval = new FinalInterval(start, end);
		
	long starttime, endtime;	
	starttime = System.currentTimeMillis();

		final Img<FloatType> normal = ops.create().img(paddedDimensions,
			new FloatType());

		endtime = System.currentTimeMillis();
		//System.out.println("create " + (endtime - starttime));
		starttime = System.currentTimeMillis();

		final RandomAccessibleInterval<FloatType> temp = Views.interval(Views
			.zeroMin(normal), convolutionInterval);

		LoopBuilder.setImages(temp).multiThreaded().forEachPixel(a -> a.setOne());

		endtime = System.currentTimeMillis();
		//System.out.println("set ones " + (endtime - starttime));
		starttime = System.currentTimeMillis();

		// convert to ClearBufferCl
		ClearCLBuffer cube = clij2.push(normal);
	  ClearCLBuffer gpunormal = clij2.create(cube);	
		ConvolveFFT.runConvolve(clij2, cube, psf, gpunormal, true);

//		clij2.show(gpunormal, "gpunormal");
		return gpunormal;
	
		
	}

	@Override
	public ClearCLBuffer createOutputBufferFromSource(ClearCLBuffer input) {
		ClearCLBuffer in = (ClearCLBuffer) args[0];
		return getCLIJ2().create(in.getDimensions(), NativeTypeEnum.Float);
	}

	@Override
	public String getParameterHelpText() {
		return "Image input, Image convolution_kernel, ByRef Image destination, Number num_iterations, Number Regularization_Factor";
	}

	@Override
	public String getDescription() {
		return "Applies Richardson-Lucy deconvolution using a Fast Fourier Transform using the clFFT library.";
	}

	@Override
	public String getAvailableForDimensions() {
		return "3D";
	}

	@Override
	public String getAuthorName() {
		return "Brian Northan, Robert Haase";
	}

	@Override
	public String getInputType() {
		return "Image";
	}

	@Override
	public String getOutputType() {
		return "Image";
	}

	@Override
	public String getCategories() {
		return "Filter,Deconvolve";
	}
}

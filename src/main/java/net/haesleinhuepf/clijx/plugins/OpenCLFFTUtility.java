
package net.haesleinhuepf.clijx.plugins;

import net.haesleinhuepf.clij2.CLIJ2;
import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
import net.haesleinhuepf.clij.coremem.enums.NativeTypeEnum;
import net.imagej.ops.OpService;
import net.imagej.ops.filter.fftSize.NextSmoothNumber;
import net.imagej.ops.filter.pad.DefaultPadInputFFT;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.outofbounds.OutOfBoundsMirrorFactory;
import net.imglib2.outofbounds.OutOfBoundsMirrorFactory.Boundary;
import net.imglib2.type.numeric.complex.ComplexFloatType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;

import org.jocl.NativePointerObject;

public class OpenCLFFTUtility {

	/**
	 * run CLIJ FFT on an RAI and return output as RAI (mostly used for testing
	 * purposes)
	 * 
	 * @param img
	 * @param reflectAndCenter - if true reflect and center for visualization
	 * @param ops
	 * @return
	 */
	public static RandomAccessibleInterval<ComplexFloatType> runFFT(
		RandomAccessibleInterval<FloatType> img, boolean reflectAndCenter,
		OpService ops)
	{
		// extend the image to a smooth number as clFFT does not support
		// all FFT sizes
		img = (RandomAccessibleInterval<FloatType>) ops.run(
			DefaultPadInputFFT.class, img, img, false);

		// get CLIJ and push to GPU
		CLIJ2 clij2 = CLIJ2.getInstance();
		ClearCLBuffer gpuInput = clij2.push(img);

		// run FFT
		ClearCLBuffer fft = runFFT(clij2, gpuInput);

		// pull result from GPU
		RandomAccessibleInterval<FloatType> result =
			(RandomAccessibleInterval<FloatType>) clij2.pullRAI(fft);

		// convert to Complex
		// TODO: do this without a copy (CLIJ needs complex types?)
		RandomAccessibleInterval<ComplexFloatType> resultComplex = copyAsComplex(
			result);

		if (reflectAndCenter) {
			// compute the interval of a full sized centered FFT
			Interval interval = Intervals.createMinMax(-img.dimension(0) / 2, -img
				.dimension(1) / 2, img.dimension(0) / 2, img.dimension(1) / 2);

			// reflect and center
			resultComplex = (RandomAccessibleInterval<ComplexFloatType>) Views
				.interval(Views.extend(resultComplex,
					new OutOfBoundsMirrorFactory<ComplexFloatType, RandomAccessibleInterval<ComplexFloatType>>(
						Boundary.SINGLE)), interval);
		}

		return resultComplex;
	}

	/**
	 * @param in - RAI to pad
	 * @param paddedDimensions - dimensions to pad to (dimensions will be at least
	 *          this big, but may be bigger if the next supported FFT size is
	 *          bigger)
	 * @param ops
	 * @param clij2
	 * @return
	 */
	public static ClearCLBuffer padInputFFTAndPush(
		RandomAccessibleInterval<FloatType> in, Dimensions paddedDimensions,
		OpService ops, CLIJ2 clij2)
	{
		System.out.println(in.dimension(0) + " " + in.dimension(1) + " " + in
			.dimension(2));

		RandomAccessibleInterval<FloatType> extended =
			(RandomAccessibleInterval<FloatType>) ops.run(DefaultPadInputFFT.class,
				in, paddedDimensions, false);

		System.out.println(extended.dimension(0) + " " + extended.dimension(1) +
			" " + extended.dimension(2));

		return clij2.push(extended);

	}

	/**
	 * @param psf
	 * @param paddedDimensions - dimensions to pad to (dimensions will be at least
	 *          this big, but may be bigger if the next supported FFT size is
	 *          bigger)
	 * @param ops
	 * @param clij2
	 * @return
	 */
	public static ClearCLBuffer padKernelFFTAndPush(
		RandomAccessibleInterval<FloatType> psf, Dimensions paddedDimensions,
		OpService ops, CLIJ2 clij2)
	{

		// extend and shift the PSF
		RandomAccessibleInterval<FloatType> extendedPSF = Views.zeroMin(ops.filter()
			.padShiftFFTKernel(psf, paddedDimensions));

		System.out.println("Extended PSF " + extendedPSF.dimension(0) + " " +
			extendedPSF.dimension(1) + " " + extendedPSF.dimension(2));

		long start = System.currentTimeMillis();

		// transfer PSF to the GPU and return
		return clij2.push(extendedPSF);

	}

	/**
	 * Run FFT on a CLBuffer
	 * 
	 * @param gpuImg input CLBuffer (needs to be pre-extended to an FFT friendly
	 *          size this can be done by using the padInputAndPush function)
	 * @return - output FFT as CLBuffer
	 */
	public static ClearCLBuffer runFFT(CLIJ2 clij2, ClearCLBuffer gpuImg) {
		// compute complex FFT dimension assuming Hermitian interleaved
		long[] fftDim = new long[] { (gpuImg.getWidth() / 2 + 1) * 2, gpuImg
			.getHeight() };

		// create GPU memory for FFT
		ClearCLBuffer gpuFFT = clij2.create(fftDim, NativeTypeEnum.Float);

		// use a hack to get the long pointers to in, out, context and queue.
		long l_in = ((NativePointerObject) (gpuImg.getPeerPointer()
			.getPointer())).getNativePointer();
		long l_out = ((NativePointerObject) (gpuFFT.getPeerPointer()
			.getPointer())).getNativePointer();
		long l_context = ((NativePointerObject) (clij2.getCLIJ().getClearCLContext()
			.getPeerPointer().getPointer())).getNativePointer();
		long l_queue = ((NativePointerObject) (clij2.getCLIJ().getClearCLContext()
			.getDefaultQueue().getPeerPointer().getPointer())).getNativePointer();

		// call the native code that runs the FFT
		clij2fftWrapper.fft2d_32f_lp((long) (gpuImg.getWidth()), gpuImg.getHeight(),
			l_in, l_out, l_context, l_queue);

		return gpuFFT;
	}


	static Img<ComplexFloatType> copyAsComplex(
		RandomAccessibleInterval<FloatType> in)
	{
		float[] temp = new float[(int) (in.dimension(0) * in.dimension(1))];
		int i = 0;
		for (FloatType f : Views.iterable(in)) {
			temp[i++] = f.getRealFloat();
		}

		return ArrayImgs.complexFloats(temp, new long[] { in.dimension(0) / 2, in
			.dimension(1) });
	}
	
	
	/**
	 * Pad a GPU image to the next supported FFT size using mirror out of bounds strategy.  
	 * Note.  This function is not fully implemented on the GPU, thus the image is pulled 
	 * onto the CPU, extended, then pushed back to GPU
	 *
	 * TODO fully implement this on the GPU
	 * 
	 * @param clij2
	 * @param input
	 * @param psf
	 * @param ops
	 * @return
	 */
	public static ClearCLBuffer padFFTInputMirror(CLIJ2 clij2, ClearCLBuffer input, ClearCLBuffer psf, OpService ops) {
		
		RandomAccessibleInterval img = clij2.pullRAI(input);

		// compute extended dimensions based on image and PSF dimensions
		long[] extendedSize = new long[img.numDimensions()];

		for (int d = 0; d < img.numDimensions(); d++) {
			extendedSize[d] = img.dimension(d) + psf.getDimensions()[d];
		}

		FinalDimensions extendedDimensions = new FinalDimensions(extendedSize);

		// extend image
		RandomAccessibleInterval extended = (RandomAccessibleInterval) ops.run(DefaultPadInputFFT.class, img, extendedDimensions, false,
						new OutOfBoundsMirrorFactory(OutOfBoundsMirrorFactory.Boundary.SINGLE));
	
		// push extended image and psf to GPU
		return  clij2.push(extended);
		
	}
	
	/**
	 * Pad a GPU image to the next supported FFT size using zero padding
	 * @param clij2
	 * @param input
	 * @param psf
	 * @return
	 */
	public static ClearCLBuffer padFFTInputZeros(CLIJ2 clij2, ClearCLBuffer input, ClearCLBuffer psf)
	{
		
		// calculate the next supported FFT size
		int[] paddedForFFTSize = new int[3];
		
		for (int i = 0; i < 3; i++) {
			long temp = input.getDimensions()[i]+psf.getDimensions()[i];
			paddedForFFTSize[i] = NextSmoothNumber.nextSmooth((int)temp);
		}
		
		ClearCLBuffer padded = clij2.create(paddedForFFTSize[0], paddedForFFTSize[1], paddedForFFTSize[2]);
		
		int startX = (paddedForFFTSize[0]-(int)input.getDimensions()[0])/2;
		int startY = (paddedForFFTSize[1]-(int)input.getDimensions()[1])/2;
		int startZ = (paddedForFFTSize[2]-(int)input.getDimensions()[2])/2;
	
		double average = clij2.getMeanOfAllPixels(input);
		
		clij2.set(padded, 1.);
		clij2.paste(input, padded, startX, startY, startZ);
		
		return padded;
	}
	

	/**
	 * crop an image that has been extended for FFT back to original size
	 * 
	 * @param clij2
	 * @param extended - the extended image
	 * @param cropped - cropped, should be same size as the original image the extended image will be cropped to this size
	 */
	public static void cropExtended(CLIJ2 clij2, ClearCLBuffer extended, ClearCLBuffer cropped)
	{
		
		long startX = (extended.getDimensions()[0]-(int)cropped.getDimensions()[0])/2;
		long startY = (extended.getDimensions()[1]-(int)cropped.getDimensions()[1])/2;
		long startZ = (extended.getDimensions()[2]-(int)cropped.getDimensions()[2])/2;
		
	  clij2.crop(extended, cropped, startX, startY, startZ);	
		
	}
	
	/**
	 * pad a kernel (PSF) and move origin to center.  This needs to be done as a pre-processing step before 
	 * calling FFT based convolution and/or deconvolution
	 * 
	 * @param clij2
	 * @param convolution_kernel - the psf
	 * @param extendedKernel - the extended and shifted psf will be written here
	 * @return
	 */
	public static ClearCLBuffer padShiftFFTKernel(CLIJ2 clij2, ClearCLBuffer convolution_kernel,
									ClearCLBuffer extendedKernel)
	{
		long psfHalfWidth = convolution_kernel.getWidth() / 2;
		long psfHalfHeight = convolution_kernel.getHeight() / 2;
		long psfHalfDepth = convolution_kernel.getDepth() / 2;

		clij2.set(extendedKernel, 0);

		ClearCLBuffer temp = clij2.create(psfHalfWidth, psfHalfHeight,
				psfHalfDepth);

		moveCorner(clij2, convolution_kernel, temp, extendedKernel, 0, 0, 0);
		moveCorner(clij2, convolution_kernel, temp, extendedKernel, 0, 0, 1);
		moveCorner(clij2, convolution_kernel, temp, extendedKernel, 0, 1, 0);
		moveCorner(clij2, convolution_kernel, temp, extendedKernel, 0, 1, 1);
		moveCorner(clij2, convolution_kernel, temp, extendedKernel, 1, 0, 0);
		moveCorner(clij2, convolution_kernel, temp, extendedKernel, 1, 0, 1);
		moveCorner(clij2, convolution_kernel, temp, extendedKernel, 1, 1, 0);
		moveCorner(clij2, convolution_kernel, temp, extendedKernel, 1, 1, 1);

		clij2.release(temp);

		return extendedKernel;
	}

	/**
	 * normalize a PSF so the sum of all voxels is 1.0
	 * 
	 * @param clij2
	 * @param input
	 * @param destination
	 * @return
	 */
	public static boolean normalize(CLIJ2 clij2, ClearCLBuffer input, ClearCLBuffer destination)
	{
		double sum = clij2.getSumOfAllPixels(input);

		clij2.multiplyImageAndScalar(input, destination, 1/sum);

		return true;
	}


	/**
	 * Moves a quadrant of an image stack in a corner by mirroring it
	 */
	private static void moveCorner(CLIJ2 clij2, ClearCLBuffer convolution_kernel,
								   ClearCLBuffer temp, ClearCLBuffer extendedKernel, int factorX, int factorY,
								   int factorZ)
	{
		clij2.crop(convolution_kernel, temp, temp.getWidth() * factorX, temp
				.getHeight() * factorY, temp.getDepth() * factorZ);
		clij2.paste(temp, extendedKernel, (extendedKernel.getWidth() - temp
				.getWidth()) * (1.0 - factorX), (extendedKernel.getHeight() - temp
				.getHeight()) * (1.0 - factorY), (extendedKernel.getDepth() - temp
				.getDepth()) * (1.0 - factorZ));
	}

}
